package com.renewflow.eip.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3：臨時資料庫的清理範圍。全部用替身（記錄 CREATE／DROP 呼叫的假資料庫），
 * 不碰任何真實資料庫，因此「碰撞」「部分失敗」都只是模擬，不會有東西被刪。
 */
class TemporaryDatabasesTest {

    /** 假的資料庫伺服器：existing 是「本來就存在」的庫；failOnCreate 是建立時會失敗的庫。 */
    static final class FakeAdmin implements TemporaryDatabases.Admin {
        final Set<String> existing = new HashSet<>();
        final Set<String> failOnCreate = new HashSet<>();
        final List<String> createCalls = new ArrayList<>();
        final List<String> dropCalls = new ArrayList<>();

        @Override
        public void create(String name) throws SQLException {
            createCalls.add(name);
            if (existing.contains(name)) {
                throw new SQLException("database \"" + name + "\" already exists", "42P04");
            }
            if (failOnCreate.contains(name)) {
                throw new SQLException("simulated failure");
            }
            existing.add(name);
        }

        @Override
        public void drop(String name) {
            dropCalls.add(name);
            existing.remove(name);
        }
    }

    @Test
    @DisplayName("R3 正常：建立兩個、清理兩個；名稱含本次識別碼，允許組以 _test 結尾")
    void normalLifecycle() throws Exception {
        FakeAdmin admin = new FakeAdmin();
        TemporaryDatabases temp = new TemporaryDatabases(admin, List.of("renewflow", "renewflow_test"));
        String refused = temp.create(temp.nameFor("refused", false));
        String allowed = temp.create(temp.nameFor("allowed", true));

        temp.close();

        assertThat(allowed).endsWith("_test").startsWith(TemporaryDatabases.PREFIX);
        assertThat(refused).doesNotEndWith("_test");
        assertThat(admin.dropCalls).containsExactly(allowed, refused);
        assertThat(temp.created()).isEmpty();
    }

    @Test
    @DisplayName("R3 名稱碰撞：同名庫已存在 → 建立失敗，且清理時絕不刪除那個既有的庫；也不會先 DROP 來騰出名稱")
    void collisionNeverDropsExistingDatabase() throws Exception {
        FakeAdmin admin = new FakeAdmin();
        TemporaryDatabases temp = new TemporaryDatabases(admin, List.of("renewflow", "renewflow_test"));
        String name = temp.nameFor("refused", false);
        admin.existing.add(name); // 模擬「有人剛好有同名的庫」

        assertThatThrownBy(() -> temp.create(name)).isInstanceOf(SQLException.class);
        temp.close();

        assertThat(admin.dropCalls).as("沒有任何 DROP").isEmpty();
        assertThat(admin.existing).as("既有的庫還在").contains(name);
    }

    @Test
    @DisplayName("R3 部分失敗：第一個建立成功、第二個失敗 → 只清理第一個")
    void partialFailureCleansOnlyWhatWasCreated() throws Exception {
        FakeAdmin admin = new FakeAdmin();
        TemporaryDatabases temp = new TemporaryDatabases(admin, List.of("renewflow", "renewflow_test"));
        String first = temp.create(temp.nameFor("refused", false));
        String second = temp.nameFor("allowed", true);
        admin.failOnCreate.add(second);

        assertThatThrownBy(() -> temp.create(second)).isInstanceOf(SQLException.class);
        temp.close();

        assertThat(admin.dropCalls).containsExactly(first);
    }

    @Test
    @DisplayName("R3 只接受本次產生的名稱；開發庫、測試庫、其他執行的臨時庫都不能建立（因此也不會被清理）")
    void refusesForeignOrProtectedNames() {
        FakeAdmin admin = new FakeAdmin();
        TemporaryDatabases temp = new TemporaryDatabases(admin, List.of("renewflow", "renewflow_test"));
        TemporaryDatabases other = new TemporaryDatabases(admin, List.of("renewflow", "renewflow_test"));

        assertThatThrownBy(() -> temp.create("renewflow")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> temp.create("renewflow_test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> temp.create(other.nameFor("refused", false))).as("別次執行的名稱").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> temp.create("eip_tmp_x; drop database renewflow")).isInstanceOf(IllegalArgumentException.class);
        assertThat(admin.createCalls).isEmpty();
    }

    @Test
    @DisplayName("R3 清理時某一個 DROP 失敗：其餘仍會嘗試，失敗的那個保留記錄並回報")
    void dropFailureIsReportedWithoutSkippingOthers() throws Exception {
        List<String> dropped = new ArrayList<>();
        TemporaryDatabases.Admin admin = new TemporaryDatabases.Admin() {
            @Override
            public void create(String name) {
            }

            @Override
            public void drop(String name) throws SQLException {
                if (name.endsWith("_allowed_test")) {
                    throw new SQLException("still in use");
                }
                dropped.add(name);
            }
        };
        TemporaryDatabases temp = new TemporaryDatabases(admin, List.of("renewflow", "renewflow_test"));
        String refused = temp.create(temp.nameFor("refused", false));
        String allowed = temp.create(temp.nameFor("allowed", true));

        assertThatThrownBy(temp::close).isInstanceOf(SQLException.class);
        assertThat(dropped).containsExactly(refused);
        assertThat(temp.created()).containsExactly(allowed);
    }
}
