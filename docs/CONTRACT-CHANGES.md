# Review contract changes

Compare saved versions before moving tests to a changed contract. The comparison reads stored contracts and scenarios; it does not execute requests, call an AI provider or change saved expectations.

## Review a price boundary change

1. Open a project, load/import the sample Catalog contract, generate `POST /products` and execute its minimum-price case. The original contract permits zero; the bundled fixture returns 400 instead of the expected 201.
2. Import another version **into the same project**, changing the request schema's `price.minimum` from 0 to 1. Keep unrelated documentation unchanged to isolate this change. You can also update the documented response schema deliberately; it will appear as a separate change.
3. In **Review contract changes**, select the older/newer versions and the saved plan for the older version. Select **Compare contracts**.
4. Expand changes to inspect before/after values and original source pointers where available. The old boundary case is AFFECTED through its represented MINIMUM rule. Other cases in the changed operation still require review.
5. Include the boundary case and choose **Propose price · MINIMUM from the newer contract**. The review shows price 0 beside price 1. Expected HTTP 201 and all response checks remain the same. No case is selected or confirmed by default.
6. Confirm the selected cases were reviewed and select **Create reviewed plan for version …**. This saves a separate plan tied to the newer specification, with new case revisions beginning at 1. It does not execute the plan.
7. Review the execution target and issue a new execution approval. An approval from the original plan cannot execute the new one. The sandbox accepts price 1. **Contract update history** shows the preserved old request/result and the new request/result together.

The original failure is not erased or relabelled as a pass. A newly reviewed input tests a different contract version. Pass/fail changes can also reflect different targets or fixture state; the comparison does not establish a cause. The history panel shows targets explicitly and links to recorded reports.

## What is compared

Both versions must belong to the same active project, with the source version older than the target. An optional assessed plan must belong to the source specification. Operation keys are HTTP method plus contract path; additions/removals are reported as such, without guessing operation renames.

Normalized operation data includes locally resolved references and merged parameters from the existing bounded reader. The structural comparison recognizes numeric response status additions/removals and JSON schema `required` lists, textual `type`, numeric `minimum`/`maximum`, string `minLength`/`maxLength`, and `enum` changes. Supported keywords can appear in request or response schemas. Required-list reordering and scalar number formatting alone do not produce a change. Other operation changes—including description, pattern, composed-schema and parameter-array differences—are conservatively UNKNOWN. Recognized structural changes are not automatically classified as breaking/nonbreaking compatibility changes.

The comparison also checks global document data other than `paths`, `components` and `info.title`/`info.version`. Changes there and changes to saved supporting documentation have UNKNOWN semantic impact across the assessed plan. Resolved changes to referenced components appear in their operation; unused component definitions are outside this comparison. Components referenced only indirectly through security requirement names, unsupported reference siblings and reader-normalization omissions are not resolved by this feature. It is not a full OpenAPI compatibility checker, business-rule interpreter or deployed-API drift detector.

At most 256 change records are returned. If more are detected, `limited:true` marks every assessed case UNKNOWN and suppresses input proposals. A truncated comparison is never used to declare a case unaffected. Source pointers are best-effort JSON Pointers in each original version. Merged parameter arrays and unavailable/removed values can have no exact original pointer; the normalized change pointer is still shown.

## Test impact and input proposals

Impact uses the same deterministic matching as the supported rule coverage panel: saved input probes and expected status, not generated titles or an AI confidence score. The rule identity includes specification ID, source JSON Pointer and probe kind. An edited case that no longer represents a probe loses that link; generation history alone does not prove coverage.

| State | Meaning |
| --- | --- |
| AFFECTED | A represented supported request rule changed, or the operation was removed |
| REVIEW | The operation changed without a matching supported request probe; inspect requests and response checks manually |
| UNKNOWN | Unsupported/context data changed or the comparison budget was exceeded; no deterministic input proposals are supplied |
| NO_DETECTED_CHANGE | No compared operation/global change was found for this plan; this is not a compatibility guarantee |

Links cover the first 64 supported top-level JSON request probes from the existing coverage engine. Response-schema/type/status changes are visible structurally but do not automatically rewrite saved response assertions or infer every affected business behavior. Required-list changes mark represented missing-field probes for review; they do not automatically fill required values or delete test cases. Probes absent from this subset remain outside known impact.

Proposals are available only when a linked numeric/length/enum input probe has a corresponding target probe with the **same expected status**. A proposal changes one top-level input field to the new probe's value and preserves all other request fields. The server recomputes the proposal rather than accepting arbitrary request data from the browser. The UI permits one proposal per copied case; if several fields need changes, review and edit the new plan afterward.

**Keep original input and checks after manual review** is an explicit alternative, including for UNKNOWN impact. That choice can deliberately retain an invalid input as a negative or investigation case. Review the expectation yourself. Copying still runs ordinary scenario validation against the target operation: removed statuses, invented query parameters or changed paths can make the copy invalid. A removed operation cannot be copied. Generate or author tests for a supported target operation instead of guessing a replacement.

## Preservation and approval

Creation requires the complete current source revision list, unique selected case indexes and explicit review. A source edit after comparison returns 409 before saving. Target versions are immutable; importing another version does not change the version the user reviewed. The normal target documentation conflict gate still applies.

The new plan gets a fresh UUID, new target contract evidence and retained titles/categories, inputs selected by the user, unchanged expected statuses and unchanged response assertions. Its rationale identifies the source plan/case/revision. The original rationale and complete request/check snapshot remain in the adoption record. Source evidence, plan revisions, approvals and execution results are preserved. No approval or result is copied to the new plan.

Migration V6 adds `plan_adoptions`, with cascading foreign keys to source and target plans. Saving the plan, its initial revision and the adoption record is one transaction. The record stores selected actions, rule links, source revision indexes and both initial snapshots. Deleting the owning project removes this history with the rest of the workspace.

A client-generated creation UUID identifies the reviewed selection. An identical repeated POST returns the existing new plan; reuse with different selections returns 409. The browser keeps that UUID for retries during the current review, but a page reload can produce another copy if the user submits again. Plan creation has no HTTP side effects; check saved plans before creating duplicates after a lost response.

History shows the latest saved execution of each exact original source revision and the current revision of each mapped new case. It keeps the initial reviewed target snapshot separately, even if the new plan is later edited. Missing matching executions remain labelled unrun. These runs may be on different targets and are not a controlled performance or causal comparison. Version-scoped coverage is recalculated for the new plan; old results never count as executions of the new plan.

## API

- `GET /api/projects/{id}/contract-diff?from={sourceSpecId}&to={targetSpecId}&planId={sourcePlanId}` returns changes, impact states, supported proposals and the complete source revision list. Omit `planId` for changes only.
- `POST /api/plans/{sourcePlanId}/contract-update` creates the reviewed copy. `KEEP` requires `ruleId:null`; `ADOPT_INPUT` requires a proposal's target `ruleId` from the current comparison.
- `GET /api/plans/{newPlanId}/contract-update` returns the persisted adoption, original matching results, current matching results and current target revisions. Ordinary plans without adoption history return 404.

Example request (replace UUIDs and the rule identifier with values from your workspace):

```json
{
  "id": "client-generated-uuid",
  "targetSpecificationId": "newer-specification-uuid",
  "expectedRevisions": [1, 1, 1],
  "choices": [
    {"scenarioIndex": 2, "action": "ADOPT_INPUT", "ruleId": "target-rule-id-from-comparison"}
  ],
  "reviewed": true
}
```

All operations remain single-user/local with the existing capacity, destination and approval restrictions. This feature does not migrate an old approval or silently change a failed expectation.
