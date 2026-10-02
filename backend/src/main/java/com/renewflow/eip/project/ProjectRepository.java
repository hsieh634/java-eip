package com.renewflow.eip.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByOrderByCodeAsc();

    List<Project> findByActiveTrueOrderByCodeAsc();

    /**
     * 新增專案：代碼重複時不丟例外、回傳空（由唯一限制 uq_project_code 裁決，並行建立同一代碼也只有一個成功）。
     * 為什麼不是捕捉唯一限制例外：PostgreSQL 在約束錯誤後會把整個交易標成失敗，之後不能再做任何查詢。
     */
    @Query(value = """
            insert into project (code, name, description, active, created_at, updated_at)
            values (:code, :name, :description, true, :now, :now)
            on conflict (code) do nothing
            returning id
            """, nativeQuery = true)
    Optional<Long> insertIfCodeFree(@Param("code") String code,
                                    @Param("name") String name,
                                    @Param("description") String description,
                                    @Param("now") Instant now);

    /** 版本相符才更新（名稱、說明、啟用），同時 version + 1。回傳 0＝不存在或已被別人改過。代碼不在這裡，建立後不可修改。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update project set name = :name, description = :description, active = :active,
                   version = version + 1, updated_at = :now
             where id = :id and version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersion(@Param("id") Long id,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("name") String name,
                        @Param("description") String description,
                        @Param("active") boolean active,
                        @Param("now") Instant now);

    /**
     * 工時寫入時鎖住目標專案（FOR SHARE）並讀取最新狀態，持有到工時交易結束。
     * 停用專案的 UPDATE 需要更強的列鎖，會等進行中的工時交易提交；工時交易也會等進行中的停用提交後才讀到「已停用」。
     * 多筆工時同時寫入同一個專案時，共享鎖彼此不阻擋。
     */
    @Query(value = "select * from project where id = :id for share", nativeQuery = true)
    Optional<Project> lockForTimesheet(@Param("id") Long id);
}
