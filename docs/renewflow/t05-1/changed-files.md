# T05.1 完整變更範圍

## 第1輪 R1／R2與STMAX客戶展示（2026-10-13）

基準47f7fdfec33513bde50396e2864575f4c3f6e054；以下為本批完整提交範圍，不含秘密／執行產物：

- `README.md`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveDtos.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveHistoryRepository.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveRequestRepository.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveRequestService.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveViews.java`
- `backend/src/test/java/com/renewflow/eip/leave/LeaveReviewScopeIntegrationTest.java`
- `docs/learning/T05-1-review-scope-and-customer-demo.md`
- `docs/renewflow/demo-stmax-customer.md`
- `docs/renewflow/development-handoff.md`
- `docs/renewflow/roadmap.md`
- `docs/renewflow/t05-1/api.md`
- `docs/renewflow/t05-1/changed-files.md`
- `docs/renewflow/t05-1/design.md`
- `docs/renewflow/t05-1/review-and-archive.md`
- `docs/renewflow/t05-1/review-round-1-verification.md`
- `docs/renewflow/t05-1/review-round-1.md`
- `docs/renewflow/t05-1/specification.md`
- `docs/renewflow/t05-1/tasks.md`
- `docs/renewflow/t05-1/verification.md`
- `docs/renewflow/version-log.md`
- `frontend/e2e/leave-revisions-review.mjs`
- `frontend/e2e/leave.mjs`
- `frontend/e2e/withdraw-confirm-controlled.mjs`
- `frontend/package.json`
- `frontend/src/api/leave.ts`
- `frontend/src/leave/LeaveDetailDialog.tsx`
- `frontend/src/leave/LeaveDocumentSummary.tsx`
- `frontend/src/leave/LeaveList.tsx`
- `frontend/src/pages/LeavePage.tsx`
- `tools/seed-stmax-demo.mjs`

以下保留前批歷史。

基準72ba3265037a7cd09c49f3f018b0ee408170f87e；全部本輪新增／修改。未改共用HTTP／認證／useLoad、定位／工時程式或既有V1～V8。

- `README.md`
- `backend/src/main/java/com/renewflow/eip/attendance/AttendanceEvidenceRepository.java`
- `backend/src/main/java/com/renewflow/eip/attendance/AttendanceEvidenceService.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveAdminService.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveAuditLog.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveController.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveDocumentRepository.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveDtos.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveEnums.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveHistory.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveHistoryRepository.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveRequest.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveRequestRepository.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveRequestService.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveRevisionRepository.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveRevisionService.java`
- `backend/src/main/java/com/renewflow/eip/leave/LeaveViews.java`
- `backend/src/main/resources/db/migration/V9__leave_revisions.sql`
- `backend/src/test/java/com/renewflow/eip/attendance/AttendanceSchemaTest.java`
- `backend/src/test/java/com/renewflow/eip/leave/LeaveAuditRollbackIntegrationTest.java`
- `backend/src/test/java/com/renewflow/eip/leave/LeaveRevisionIntegrationTest.java`
- `backend/src/test/java/com/renewflow/eip/leave/LeaveRevisionSchemaTest.java`
- `backend/src/test/java/com/renewflow/eip/leave/LeaveSchemaTest.java`
- `backend/src/test/java/com/renewflow/eip/organization/OrganizationSchemaTest.java`
- `backend/src/test/java/com/renewflow/eip/support/TestData.java`
- `docs/learning/T05-1-leave-revisions-and-cancellation.md`
- `docs/renewflow/04-data-model.md`
- `docs/renewflow/05-api-design.md`
- `docs/renewflow/06-security-design.md`
- `docs/renewflow/development-handoff.md`
- `docs/renewflow/product-direction.md`
- `docs/renewflow/roadmap.md`
- `docs/renewflow/t03-3/review-and-archive.md`
- `docs/renewflow/t03-3/review-round-1.md`
- `docs/renewflow/t03-3/tasks.md`
- `docs/renewflow/t05-1/api.md`
- `docs/renewflow/t05-1/changed-files.md`
- `docs/renewflow/t05-1/design.md`
- `docs/renewflow/t05-1/manual-verification.md`
- `docs/renewflow/t05-1/migration-evidence.md`
- `docs/renewflow/t05-1/mutation-evidence.md`
- `docs/renewflow/t05-1/proposal.md`
- `docs/renewflow/t05-1/review-and-archive.md`
- `docs/renewflow/t05-1/specification.md`
- `docs/renewflow/t05-1/tasks.md`
- `docs/renewflow/t05-1/verification.md`
- `docs/renewflow/t05/04-data-model.md`
- `docs/renewflow/t05/05-api-design.md`
- `docs/renewflow/t05/06-security-design.md`
- `docs/renewflow/version-log.md`
- `frontend/e2e/attendance-target-review.mjs`
- `frontend/e2e/leave-revisions.mjs`
- `frontend/package.json`
- `frontend/src/api/leave.ts`
- `frontend/src/leave/LeaveDetailDialog.tsx`
- `frontend/src/leave/LeaveDocumentSummary.tsx`
- `frontend/src/leave/LeaveList.tsx`
- `frontend/src/leave/LeaveRevisionDialog.tsx`
- `frontend/src/leave/labels.ts`
- `frontend/src/leave/revisionOutcome.ts`
- `frontend/src/pages/LeaveApprovalsPage.tsx`
- `frontend/src/pages/LeavePage.tsx`
