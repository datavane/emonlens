package io.emonlens.server.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.TraceSummaryEntity;

/**
 * 采样决策红线：预算与比例都是确定性规则（V1.1 §13.3），不允许模型参与。
 */
class SamplingServiceTest {

    private TraceSummaryEntity summary(int errorCount) {
        TraceSummaryEntity summary = new TraceSummaryEntity();
        summary.setErrorCount(errorCount);
        return summary;
    }

    @Test
    void rateZeroDisablesAutoEval() {
        EmonLensProperties properties = new EmonLensProperties();
        properties.getEval().setDefaultRate(0);
        SamplingService service = new SamplingService(properties, new io.emonlens.server.config.SysSettingService(
                org.mockito.Mockito.mock(io.emonlens.server.storage.mapper.SysSettingMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        assertThat(service.shouldAutoEvaluate(summary(0))).isFalse();
    }

    @Test
    void rateOneAlwaysSamples() {
        EmonLensProperties properties = new EmonLensProperties();
        properties.getEval().setDefaultRate(1.0);
        SamplingService service = new SamplingService(properties, new io.emonlens.server.config.SysSettingService(
                org.mockito.Mockito.mock(io.emonlens.server.storage.mapper.SysSettingMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        for (int i = 0; i < 5; i++) {
            assertThat(service.shouldAutoEvaluate(summary(0))).isTrue();
        }
    }

    @Test
    void hourlyBudgetCapsSampling() {
        EmonLensProperties properties = new EmonLensProperties();
        properties.getEval().setDefaultRate(1.0);
        properties.getEval().setMaxPerHour(2);
        SamplingService service = new SamplingService(properties, new io.emonlens.server.config.SysSettingService(
                org.mockito.Mockito.mock(io.emonlens.server.storage.mapper.SysSettingMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        assertThat(service.shouldAutoEvaluate(summary(0))).isTrue();
        assertThat(service.shouldAutoEvaluate(summary(0))).isTrue();
        assertThat(service.shouldAutoEvaluate(summary(0))).isFalse();
    }
}
