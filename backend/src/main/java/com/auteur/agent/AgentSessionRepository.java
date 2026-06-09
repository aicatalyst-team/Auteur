package com.auteur.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    List<AgentSession> findAllByOrderByUpdatedAtDesc();
}
