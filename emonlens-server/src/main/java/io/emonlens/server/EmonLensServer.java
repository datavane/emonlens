package io.emonlens.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.emonlens.server.config.EmonLensProperties;

/**
 * EmonLens 单部署单元：api + ingest + engine + worker 全部内嵌。
 * 水平扩展：实例无状态，后台任务经 DB 原子认领协调，多实例直接横向加副本。
 */
@SpringBootApplication(scanBasePackages = "io.emonlens")
@EnableConfigurationProperties(EmonLensProperties.class)
@EnableScheduling
public class EmonLensServer {

    public static void main(String[] args) {
        SpringApplication.run(EmonLensServer.class, args);
    }
}
