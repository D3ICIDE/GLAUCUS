package utils;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;


public final class ModelFactory {
    private ModelFactory() {}

    public static ChatModel gemini(String modelName) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(Config.geminiApi)
                .modelName(modelName)
                .returnThinking(true)
                .sendThinking(true)
                .build();
    }

    public static StreamingChatModel geminiStreaming(String modelName) {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(Config.geminiApi)
                .modelName(modelName)
                .returnThinking(true)
                .sendThinking(true)
                .build();
    }

    /** Any OpenAI-compatible endpoint (Groq, OpenRouter, TokenRouter, NVIDIA ...). */
    public static ChatModel openAiCompatible(String modelName, String apiKey, String baseUrl) {
        return OpenAiChatModel.builder()
                .modelName(modelName)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
    }

    public static ChatModel groq(String modelName) {
        return openAiCompatible(modelName, Config.groqApi, Config.groq_url);
    }

    public static ChatModel openRouter(String modelName) {
        return openAiCompatible(modelName, Config.openRouterApi, Config.openrouter_url);
    }
}