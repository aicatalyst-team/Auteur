package com.auteur.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface CriticLogRepository extends JpaRepository<CriticLog, Long> {

    /**
     * Agent 反思工具用:按 scriptId 拉历史 critic 评分,最新在前,带 limit 防止老脚本 200+ 条灌爆 LLM 上下文。
     * 调用方传 PageRequest.of(0, limit) 即可。
     */
    List<CriticLog> findByScriptIdOrderByCreatedAtDesc(Long scriptId, Pageable pageable);

    /** 看板 — 按 role + decision 聚合,近 N 天计数 / 平均分。结果 row[0]=role, row[1]=decision, row[2]=cnt, row[3]=avgScore。 */
    @Query(value =
            "SELECT role, decision, COUNT(*) AS cnt, AVG(score) AS avg_score " +
            "FROM critic_log WHERE created_at >= :since " +
            "GROUP BY role, decision",
            nativeQuery = true)
    List<Object[]> aggregateSince(LocalDateTime since);

    /** 高频 issue —— 简化版:把 issues_json 当字符串模糊聚合。前端看板要细分时再上分词。 */
    @Query(value =
            "SELECT issues_json, COUNT(*) AS cnt " +
            "FROM critic_log WHERE created_at >= :since AND decision = 'REWRITE' " +
            "GROUP BY issues_json ORDER BY cnt DESC LIMIT 10",
            nativeQuery = true)
    List<Object[]> topIssuesSince(LocalDateTime since);
}
