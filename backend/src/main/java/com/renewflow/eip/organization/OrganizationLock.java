package com.renewflow.eip.organization;

import com.renewflow.eip.identity.AccountState;
import com.renewflow.eip.identity.AccountStatusService;
import com.renewflow.eip.identity.EmployeeUserDetails;
import com.renewflow.eip.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 組織與請假寫入共用的序列化鎖：PostgreSQL 交易級 advisory lock（固定鍵）。
 *
 * <p>為什麼需要：主管不可循環、至少一位啟用中的 ADMIN、停用部門沒有啟用員工、請假時段不重疊、
 * 審核人必須仍有效——這些規則都跨越多列資料。版本號只能擋「同一筆」的過期表單；
 * 例如 A→B 與 B→A 同時修改的是不同兩筆，各自版本都正確，合起來卻是循環。
 * 讓這些寫入在「讀取 → 檢查 → 寫入」之前先取得同一把鎖，它們就會一個接一個執行，檢查時看到的一定是最新狀態。
 *
 * <p>範圍（T05 起）：組織管理（部門、員工的建立與修改、dev 示範組織初始化）、
 * 請假寫入（送出、核准、退回、撤回、改派）與指定請假審核人設定；T05.2流程草稿／發布及一致性預覽也沿用此鎖。這些操作彼此排隊。
 * 登入、打卡、工時與所有查詢都不取這把鎖——但它們仍可能因其他 SQL 列鎖而等待，這把鎖不保證「完全不會等待」。
 * 鎖在交易結束（提交或回滾）時自動釋放。鎖定順序：先取這把鎖，之後才讀寫員工、部門與請假資料列。
 */
@Component
public class OrganizationLock {

    /** 固定的鎖鍵（整個系統只有上述寫入使用它）。 */
    static final long KEY = 7_050_001L;

    private final JdbcTemplate jdbc;
    private final AccountStatusService accountStatusService;

    public OrganizationLock(JdbcTemplate jdbc, AccountStatusService accountStatusService) {
        this.jdbc = jdbc;
        this.accountStatusService = accountStatusService;
    }

    /** 必須在交易內呼叫（MANDATORY）：沒有交易就沒有「持有到交易結束」的鎖。給沒有登入者的系統流程使用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, KEY);
    }

    /**
     * 取鎖，再重新確認操作者：請求通過 ActiveAccountFilter 之後可能在這裡等待；等待期間若被停用、刪除或改了角色，
     * 不能再用舊權限繼續。失效 → 401 UNAUTHENTICATED（交易內尚未寫入任何東西；下一個請求由 Filter 作廢 Session）。
     * 讀取帳號狀態時資料庫故障 → 例外原樣往外丟（500），不冒充未登入。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireFor(EmployeeUserDetails actor) {
        acquire();
        if (accountStatusService.check(actor) != AccountState.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "帳號狀態已變更（停用或角色調整），請重新登入");
        }
    }
}
