# T05.1 變異驗證

2026-10-13，基準72ba326之本批工作區。只在專用測試庫改服務，V9不變。每次以完整原bytes還原，checksum相同，再重跑對應方法。完整最終後端另跑345項。

| 變異 | 真HTTP對應方法 | 變異結果 | 還原結果 |
|---|---|---|---|
| 移除有效快照檢查 | staleEffectiveVersionAndUnauthorizedReviewHaveNoSideEffects | exit1，1 assertion Failure／0 Error | exit0，1通過／0失敗 |
| 忽略原UUID提交 | dedupAfterTerminalAndSettingsChangesKeepsOriginalWorkflow | exit1，1 assertion Failure／0 Error | exit0，1通過／0失敗 |
| 跳過其他文件重疊 | changeOverlapExcludesSelfButChecksOtherPendingAndEffectiveDocuments | exit1，1 assertion Failure／0 Error | exit0，1通過／0失敗 |
| 銷假待審提前切有效指標 | fullCancellationKeepsOriginalAndOnlyReleasesAfterApproval | exit1，1 assertion Failure／0 Error | exit0，1通過／0失敗 |
| 吞掉實際SUBMITTED歷程寫入後例外 | submissionAuditFailureRollsBackReplacementAndDedup | exit1，1 assertion Failure／0 Error | exit0，1通過／0失敗 |

原服務SHA-256：`32756399e9e916e255036a68682cbaedc3b3c23ecd0cb605e8cd8d85b0aae39c`；變異、還原logs：`backend/target/t05-1-mutation-<name>.log`／`-restored.log`，機器結果`backend/target/t05-1-mutations.json`（忽略，不提交執行產物）。第一次effective變異報Error，是成功201缺code導致測試讀取失敗；補409斷言後重做，表格只列可解釋的最終Failure結果。不是透過編譯失敗偽造保護有效。
