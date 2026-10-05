package com.renewflow.eip.organization;

import com.renewflow.eip.identity.Employee;
import com.renewflow.eip.identity.Role;
import com.renewflow.eip.support.TestBrowser;
import com.renewflow.eip.support.TestBrowser.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05 前置的並行測試：對真的 PostgreSQL 同時送出兩個請求（閘門同時放行），重複多輪。
 * 這些情境裡兩個請求改的是「不同的列」，各自的版本都正確——只有組織鎖能讓第二個看到第一個的結果。
 */
class OrganizationConcurrencyIntegrationTest extends OrganizationTestBase {

    private static final int ROUNDS = 8;

    private int activeInInactiveDepartments() {
        return jdbc.queryForObject("""
                select count(*) from employee e join department d on d.id = e.department_id where e.enabled and not d.active
                """, Integer.class);
    }

    /** 從任何人沿著主管鏈往上走，能回到自己就是循環（遞迴查詢，最多走 50 層）。 */
    private int employeesInCycles() {
        return jdbc.queryForObject("""
                with recursive chain(start_id, current_id, depth) as (
                    select id, manager_id, 1 from employee where manager_id is not null
                    union all
                    select c.start_id, e.manager_id, c.depth + 1 from chain c join employee e on e.id = c.current_id
                     where e.manager_id is not null and c.depth < 50 and c.current_id <> c.start_id)
                select count(distinct start_id) from chain where current_id = start_id
                """, Integer.class);
    }

    private int invalidManagers() {
        return jdbc.queryForObject("""
                select count(*) from employee e join employee m on m.id = e.manager_id where not m.enabled or m.role = 'EMPLOYEE'
                """, Integer.class);
    }

    private int activeAdmins() {
        return jdbc.queryForObject("select count(*) from employee where role = 'ADMIN' and enabled", Integer.class);
    }

    @Test
    @DisplayName("CC-01 同時設定 A→B 與 B→A：每輪恰好一個成功、另一個 409 MANAGER_CYCLE；資料庫從不出現循環")
    void concurrentOppositeManagersNeverFormCycle() throws Exception {
        Employee m1 = extra("T0020", "m_one", Role.MANAGER);
        Employee m2 = extra("T0021", "m_two", Role.MANAGER);
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update employee set manager_id = null where id in (?, ?)", m1.getId(), m2.getId());
            Form a = form(m1.getId()).manager(m2.getId());
            Form b = form(m2.getId()).manager(m1.getId());

            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(() -> a.submit(carolB), () -> b.submit(daveB)));

            List<Integer> statuses = replies.stream().map(Reply::status).sorted().toList();
            assertThat(statuses).as("round %d: %s", round, replies).containsExactly(200, 409);
            assertThat(replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().code()).isEqualTo("MANAGER_CYCLE");
            assertThat(employeesInCycles()).as("round %d", round).isZero();
        }
    }

    @Test
    @DisplayName("CC-02 兩位 ADMIN 同時把對方降級：每輪恰好一個成功；另一個 409 LAST_ADMIN 或 401（Session 已因角色變更作廢）；永遠至少一位啟用中的 ADMIN")
    void concurrentMutualDemotionKeepsOneAdmin() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update employee set role = 'ADMIN', enabled = true where id in (?, ?)", carol.getId(), dave.getId());
            TestBrowser carolB = loginAs("carol");
            TestBrowser daveB = loginAs("dave");
            Form demoteDave = form(dave.getId()).role("MANAGER");
            Form demoteCarol = form(carol.getId()).role("MANAGER");

            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(() -> demoteDave.submit(carolB), () -> demoteCarol.submit(daveB)));

            assertThat(replies.stream().filter(r -> r.status() == 200).count()).as("round %d: %s", round, replies).isEqualTo(1);
            Reply loser = replies.stream().filter(r -> r.status() != 200).findFirst().orElseThrow();
            assertThat(loser.status()).isIn(409, 401);
            if (loser.status() == 409) {
                assertThat(loser.code()).isEqualTo("LAST_ADMIN");
            }
            assertThat(activeAdmins()).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CC-03 同時「停用部門」與「把員工指派到該部門」：每輪只有一個成功；永遠沒有啟用中的員工在停用部門")
    void concurrentDeactivateAndAssign() throws Exception {
        long dept = createDept("RACE", "競爭");
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update employee set department_id = null where id = ?", alice.getId());
            jdbc.update("update department set active = true where id = ?", dept);
            String name = jdbc.queryForObject("select name from department where id = ?", String.class, dept);
            String deactivate = deptUpdate(name, false, deptVersion(dept), "停用");
            Form assign = form(alice.getId()).department(dept);

            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> carolB.put(DEPARTMENTS + "/" + dept, deactivate), () -> assign.submit(daveB)));

            assertThat(replies.stream().filter(r -> r.status() == 200).count()).as("round %d: %s", round, replies).isEqualTo(1);
            Reply loser = replies.stream().filter(r -> r.status() != 200).findFirst().orElseThrow();
            assertThat(loser.status()).isEqualTo(409);
            assertThat(loser.code()).isIn("DEPARTMENT_INACTIVE", "DEPARTMENT_HAS_ACTIVE_EMPLOYEES");
            assertThat(activeInInactiveDepartments()).as("round %d", round).isZero();
        }
    }

    @Test
    @DisplayName("CC-04 同時「停用部門」與「重新啟用該部門的停用員工」：永遠沒有啟用中的員工在停用部門")
    void concurrentDeactivateAndReenable() throws Exception {
        long dept = createDept("RACE2", "競爭二");
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update employee set department_id = ?, enabled = false where id = ?", dept, alice.getId());
            jdbc.update("update department set active = true where id = ?", dept);
            String name = jdbc.queryForObject("select name from department where id = ?", String.class, dept);
            String deactivate = deptUpdate(name, false, deptVersion(dept), "停用");
            Form reenable = form(alice.getId()).enabled(true);

            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(
                    () -> carolB.put(DEPARTMENTS + "/" + dept, deactivate), () -> reenable.submit(daveB)));

            assertThat(replies.stream().filter(r -> r.status() == 200).count()).as("round %d: %s", round, replies).isEqualTo(1);
            assertThat(activeInInactiveDepartments()).as("round %d", round).isZero();
        }
    }

    @Test
    @DisplayName("CC-05 同時「指定某人為主管」與「停用／降級那個人」：永遠沒有員工的主管是停用者或 EMPLOYEE")
    void concurrentAssignManagerAndDisableManager() throws Exception {
        TestBrowser carolB = loginAs("carol");
        TestBrowser daveB = loginAs("dave");
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update employee set manager_id = null where id = ?", alice.getId());
            jdbc.update("update employee set enabled = true, role = 'MANAGER' where id = ?", bob.getId());
            Form assign = form(alice.getId()).manager(bob.getId());
            Form disableBob = round % 2 == 0 ? form(bob.getId()).enabled(false) : form(bob.getId()).role("EMPLOYEE");

            List<Reply> replies = runSimultaneously(List.<Callable<Reply>>of(() -> assign.submit(carolB), () -> disableBob.submit(daveB)));

            assertThat(replies.stream().filter(r -> r.status() == 200).count()).as("round %d: %s", round, replies).isEqualTo(1);
            assertThat(invalidManagers()).as("round %d", round).isZero();
        }
    }
}
