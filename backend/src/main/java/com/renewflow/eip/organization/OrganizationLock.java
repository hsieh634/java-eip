package com.renewflow.eip.organization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 組織寫入的序列化鎖：PostgreSQL 交易級 advisory lock（固定鍵）。
 *
 * <p>為什麼需要：主管不可循環、至少一位啟用中的 ADMIN、停用部門沒有啟用員工——這些規則都跨越多列資料。
 * 版本號只能擋「同一筆」的過期表單；例如 A→B 與 B→A 同時修改的是不同兩筆，各自版本都正確，合起來卻是循環。
 * 讓所有組織寫入在「讀取 → 檢查 → 寫入」之前先取得同一把鎖，它們就會一個接一個執行，檢查時看到的一定是最新狀態。
 *
 * <p>範圍：只有組織管理（部門、員工的建立與修改、dev 示範組織初始化）會取這把鎖；
 * 登入、打卡、工時都不取，所以不會被組織管理拖慢。鎖在交易結束（提交或回滾）時自動釋放。
 * 鎖定順序：先取這把鎖，之後才讀寫員工與部門列。
 */
@Component
public class OrganizationLock {

    /** 固定的鎖鍵（只要整個系統中只有組織管理使用它即可）。 */
    static final long KEY = 7_050_001L;

    private final JdbcTemplate jdbc;

    public OrganizationLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 必須在交易內呼叫（MANDATORY）：沒有交易就沒有「持有到交易結束」的鎖。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, KEY);
    }
}
