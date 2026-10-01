package com.renewflow.eip.support;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 測試用臨時資料庫的建立與清理。<b>只會刪除「本次成功建立」的資料庫</b>：
 * <ul>
 *   <li>名稱由本類別產生：固定前綴＋本次隨機識別碼＋用途後綴，不會挑既有名稱；</li>
 *   <li>不先刪除任何資料庫來「騰出名稱」——名稱已存在（碰撞）就讓建立失敗；</li>
 *   <li>建立成功才記錄；清理時只刪記錄過的；建立失敗或部分成功時，絕不刪沒有記錄的庫；</li>
 *   <li>名稱不得與實際設定中的開發庫、測試庫相同。</li>
 * </ul>
 * 實際的 CREATE／DROP 透過 {@link Admin} 介面執行，因此可以用替身驗證上述規則。
 */
public final class TemporaryDatabases implements AutoCloseable {

    /** 真正對資料庫下 CREATE／DROP 的部分。 */
    public interface Admin {
        void create(String name) throws SQLException;

        void drop(String name) throws SQLException;
    }

    static final String PREFIX = "eip_tmp_";
    private static final Pattern ALLOWED_NAME = Pattern.compile(PREFIX + "[a-z0-9]{12}_[a-z]+(_test)?");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Admin admin;
    private final Set<String> protectedNames;
    private final String runId;
    private final List<String> created = new ArrayList<>();

    /**
     * @param protectedNames 絕不可建立／刪除的名稱（實際設定中的開發庫、測試庫）
     */
    public TemporaryDatabases(Admin admin, Collection<String> protectedNames) {
        this.admin = admin;
        this.protectedNames = Set.copyOf(protectedNames.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList());
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        this.runId = id.toString();
    }

    /** 本次的名稱，例如 eip_tmp_k3x9…_refused；testDatabase=true 時以 _test 結尾（符合測試庫命名）。 */
    public String nameFor(String purpose, boolean testDatabase) {
        if (!purpose.matches("[a-z]+")) {
            throw new IllegalArgumentException("purpose 只能是小寫英文字母");
        }
        return PREFIX + runId + "_" + purpose + (testDatabase ? "_test" : "");
    }

    /** 建立資料庫；成功才記錄。失敗（含名稱已存在）時例外照常丟出，且不會被記錄、不會被清理。 */
    public String create(String name) throws SQLException {
        if (!ALLOWED_NAME.matcher(name).matches() || !name.contains(runId)) {
            throw new IllegalArgumentException("只能建立本次產生的臨時資料庫名稱：" + name);
        }
        if (protectedNames.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不可使用開發庫或測試庫的名稱：" + name);
        }
        admin.create(name);
        created.add(name);
        return name;
    }

    /** 本次成功建立、將會被清理的資料庫。 */
    public List<String> created() {
        return List.copyOf(created);
    }

    /** 只刪除本次成功建立的資料庫（後建先刪）；某一個刪除失敗仍繼續處理其他的，最後一併回報。 */
    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        for (int i = created.size() - 1; i >= 0; i--) {
            String name = created.get(i);
            try {
                admin.drop(name);
                created.remove(i);
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
