package io.emonlens.server.sys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.engine.eval.HttpLlmClient;

/** 设置保存语义：Key 留空必须沿用已保存值，避免把凭据抹掉。 */
class SysSettingControllerTest {

    @Test
    void savingWithoutApiKeyKeepsStoredKey() {
        SysSettingService service = mock(SysSettingService.class);
        when(service.getEvalLlm()).thenReturn(existing("sk-old"));
        SysSettingController controller = controller(service);

        SysSettingController.EvalLlmRequest req = new SysSettingController.EvalLlmRequest();
        req.setProtocol("openai");
        req.setBaseUrl("https://opencode.ai/zen/go/v1");
        req.setModel("deepseek-v4.1-flash");
        req.setTimeoutMs(20000L);

        controller.saveEvalLlm(req);

        SysSettingService.EvalLlm saved = captureSaved(service);
        assertThat(saved.getApiKey()).isEqualTo("sk-old");
        assertThat(saved.getProtocol()).isEqualTo("openai");
        assertThat(saved.getBaseUrl()).isEqualTo("https://opencode.ai/zen/go/v1");
    }

    @Test
    void explicitApiKeyReplacesStoredKey() {
        SysSettingService service = mock(SysSettingService.class);
        when(service.getEvalLlm()).thenReturn(existing("sk-old"));
        SysSettingController controller = controller(service);

        SysSettingController.EvalLlmRequest req = new SysSettingController.EvalLlmRequest();
        req.setProtocol("anthropic");
        req.setBaseUrl("https://api.anthropic.com/v1");
        req.setApiKey("sk-ant-new");

        controller.saveEvalLlm(req);

        SysSettingService.EvalLlm saved = captureSaved(service);
        assertThat(saved.getApiKey()).isEqualTo("sk-ant-new");
        assertThat(saved.getProtocol()).isEqualTo("anthropic");
    }

    @Test
    void clearingBaseUrlClearsProtocolButKeepsKey() {
        SysSettingService service = mock(SysSettingService.class);
        when(service.getEvalLlm()).thenReturn(existing("sk-old"));
        SysSettingController controller = controller(service);

        SysSettingController.EvalLlmRequest req = new SysSettingController.EvalLlmRequest();
        req.setBaseUrl("");
        req.setProtocol("anthropic");

        controller.saveEvalLlm(req);

        SysSettingService.EvalLlm saved = captureSaved(service);
        assertThat(saved.getBaseUrl()).isNull();
        assertThat(saved.getProtocol()).isNull();
        assertThat(saved.getApiKey()).isEqualTo("sk-old");
    }

    @Test
    void invalidProtocolIsRejectedWithoutSaving() {
        SysSettingService service = mock(SysSettingService.class);
        SysSettingController controller = controller(service);

        SysSettingController.EvalLlmRequest req = new SysSettingController.EvalLlmRequest();
        req.setProtocol("grpc");
        req.setBaseUrl("https://x.dev/v1");

        var result = controller.saveEvalLlm(req);

        assertThat(result.getCode()).isNotEqualTo(0);
        verify(service, org.mockito.Mockito.never()).saveEvalLlm(org.mockito.ArgumentMatchers.any());
    }

    private SysSettingController controller(SysSettingService service) {
        return new SysSettingController(service, new EmonLensProperties(), mock(HttpLlmClient.class));
    }

    private SysSettingService.EvalLlm existing(String apiKey) {
        SysSettingService.EvalLlm cfg = new SysSettingService.EvalLlm();
        cfg.setProtocol("openai");
        cfg.setBaseUrl("https://opencode.ai/zen/go/v1");
        cfg.setApiKey(apiKey);
        cfg.setModel("deepseek-v4.1-flash");
        cfg.setTimeoutMs(20000L);
        return cfg;
    }

    private SysSettingService.EvalLlm captureSaved(SysSettingService service) {
        ArgumentCaptor<SysSettingService.EvalLlm> captor =
                ArgumentCaptor.forClass(SysSettingService.EvalLlm.class);
        verify(service).saveEvalLlm(captor.capture());
        return captor.getValue();
    }
}
