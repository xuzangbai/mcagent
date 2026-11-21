package com.mcagent.client.ai;

import java.util.List;

public interface LlmProvider {
	String chat(String systemPrompt, List<ChatMsg> history) throws Exception;
}
