package io.emonlens.server.engine.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.ingest.ContentCaptureService;
import io.emonlens.server.ingest.RedactionService;
import io.emonlens.server.storage.entity.ContentEntity;
import io.emonlens.server.storage.mapper.ContentMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

/**
 * 内容采集红线：REDACTED 模式下 PII 必须被掩码后才能落库。
 */
class ContentCaptureServiceTest {

    @Test
    void redactedModeMasksPiiBeforeStore() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        EmonLensProperties properties = new EmonLensProperties();
        properties.getContent().setCaptureMode("REDACTED");
        ContentCaptureService service = new ContentCaptureService(new RedactionService(), contentMapper, properties);

        service.capture("t-1001", "trace-1", "span-1",
                Map.of("completion", "客户手机号 13812345678，邮箱 a@b.com"));

        ArgumentCaptor<ContentEntity> captor = ArgumentCaptor.forClass(ContentEntity.class);
        verify(contentMapper, times(1)).insert(captor.capture());
        ContentEntity stored = captor.getValue();
        assertThat(stored.getBody()).doesNotContain("13812345678");
        assertThat(stored.getBody()).doesNotContain("a@b.com");
        assertThat(stored.getBody()).contains("***REDACTED-PHONE***");
        assertThat(stored.getContentHash()).hasSize(64);
    }

    @Test
    void noneModeStoresNothing() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        EmonLensProperties properties = new EmonLensProperties();
        properties.getContent().setCaptureMode("NONE");
        ContentCaptureService service = new ContentCaptureService(new RedactionService(), contentMapper, properties);

        service.capture("t-1001", "trace-1", "span-1", Map.of("prompt", "secret"));
        verify(contentMapper, times(0)).insert(any(ContentEntity.class));
    }
}
