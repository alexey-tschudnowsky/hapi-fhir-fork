package ca.uhn.fhir.jpa.mdm.svc;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.FhirPathExecutionException;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.mdm.blocklist.json.BlockListJson;
import ca.uhn.fhir.mdm.blocklist.json.BlockListRuleJson;
import ca.uhn.fhir.mdm.blocklist.json.BlockedFieldJson;
import ca.uhn.fhir.mdm.blocklist.svc.IBlockListRuleProvider;
import ca.uhn.fhir.mdm.blocklist.svc.IBlockRuleEvaluationSvc;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class BlockRuleEvaluationSvcImpl implements IBlockRuleEvaluationSvc {
	private static final Logger ourLog = getLogger(BlockRuleEvaluationSvcImpl.class);

	private final IFhirPath myFhirPath;

	private final IBlockListRuleProvider myBlockListRuleProvider;

	public BlockRuleEvaluationSvcImpl(
		FhirContext theContext,
		@Nullable IBlockListRuleProvider theIBlockListRuleProvider
	) {
		myFhirPath = theContext.newFhirPath();
		myBlockListRuleProvider = theIBlockListRuleProvider;
	}

	private boolean hasBlockList() {
		return myBlockListRuleProvider != null && myBlockListRuleProvider.getBlocklistRules() != null;
	}

	@Override
	public boolean isMdmMatchingBlocked(IAnyResource theResource) {
		if (hasBlockList()) {
			return isMdmMatchingBlockedInternal(theResource);
		}
		return false;
	}

	private boolean isMdmMatchingBlockedInternal(IAnyResource theResource) {
		BlockListJson blockListJson = myBlockListRuleProvider.getBlocklistRules();
		String resourceType = theResource.fhirType();

		// gather only applicable rules
		List<BlockListRuleJson> rulesForResource = blockListJson.getBlockListItemJsonList()
			.stream().filter(r -> r.getResourceType().equals(resourceType))
			.collect(Collectors.toList());

		for (BlockListRuleJson rule : rulesForResource) {
			// these rules are 'or''d, so if any match,
			// mdm matching is blocked
			if (isMdmBlockedForFhirPath(theResource, rule)) {
				return true;
			}
		}
		// otherwise, do not block
		return false;
	}

	private boolean isMdmBlockedForFhirPath(IAnyResource theResource, BlockListRuleJson theRule) {
		List<BlockedFieldJson> blockedFields = theRule.getBlockedFields();

		// rules are 'and'ed
		// This means that if we detect any reason *not* to block
		// we don't; only if all block rules pass do we block
		for (BlockedFieldJson field : blockedFields) {
			String path = field.getFhirPath();
			String blockedValue = field.getBlockedValue();

			List<IBase> results;
			try {
				// can throw FhirPathExecutionException if path is incorrect
				// or functions are invalid.
				// single() explicitly throws this (but may be what is desired)
				// so we'll catch and not block if this fails
				results = myFhirPath.evaluate(theResource, path, IBase.class);
			} catch (FhirPathExecutionException ex) {
				ourLog.warn("FhirPath evaluation failed with an exception."
					+ " No blocking will be applied and mdm matching will continue as before.", ex);
				return false;
			}

			// fhir path should return exact values
			if (results.size() != 1) {
				// no results means no blocking
				// too many matches means no blocking
				ourLog.trace("Too many values at field {}", path);
				return false;
			}

			IBase first = results.get(0);

			if (isPrimitiveType(first.fhirType())) {
				IPrimitiveType<?> primitiveType = (IPrimitiveType<?>) first;
				if (!primitiveType.getValueAsString().equalsIgnoreCase(blockedValue)) {
					// doesn't match
					// no block
					ourLog.trace("Value at path {} does not match - mdm will not block.", path);
					return false;
				}
			} else {
				// blocking can only be done by evaluating primitive types
				// additional fhirpath values required
				ourLog.warn("FhirPath {} yields a non-primitive value; blocking is only supported on primitive field types.", path);
				return false;
			}
		}

		// if we got here, all blocking rules evaluated to true
		return true;
	}

	private boolean isPrimitiveType(String theFhirType) {
		switch (theFhirType) {
			default:
				// non-primitive type (or unknown type)
				return false;
			case "string":
			case "code":
			case "markdown":
			case "id":
			case "uri":
			case "url":
			case "canonical":
			case "oid":
			case "uuid":
			case "boolean":
			case "unsignedInt":
			case "positiveInt":
			case "decimal":
			case "integer64":
			case "date":
			case "dateTime":
			case "time":
			case "instant":
			case "base6Binary":
				return true;
		}
	}
}