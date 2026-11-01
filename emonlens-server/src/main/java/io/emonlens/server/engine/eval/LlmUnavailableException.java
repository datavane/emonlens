package io.emonlens.server.engine.eval;

/** LLM Provider 不可用（网络/超时/非 2xx）。 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
