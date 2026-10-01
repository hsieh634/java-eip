package com.renewflow.eip.identity;

/** 以資料庫最新狀態判斷「這個 Session 代表的帳號現在還有效嗎」的結果。 */
public enum AccountState {
    ACTIVE,
    /** 員工已被停用或刪除。 */
    DISABLED_OR_DELETED,
    /** 角色與 Session 內的快照不同：Session 的權限已過時，需要重新登入。 */
    ROLE_CHANGED
}
