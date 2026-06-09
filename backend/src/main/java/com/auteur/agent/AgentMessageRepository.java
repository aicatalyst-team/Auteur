package com.auteur.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {
    List<AgentMessage> findBySessionIdOrderBySeqAsc(Long sessionId);
    int countBySessionId(Long sessionId);
}
