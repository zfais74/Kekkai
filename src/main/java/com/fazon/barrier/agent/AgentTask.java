package com.fazon.barrier.agent;

import java.util.Map;

/**
 * The task for an agent to execute. You can think of this as a function that takes a context and returns a result.
 *
 */
public interface AgentTask {
    Object execute(Map<String, Object> readOnlyContext);
}
