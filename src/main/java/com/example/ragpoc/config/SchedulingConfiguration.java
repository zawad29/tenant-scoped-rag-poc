package com.example.ragpoc.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled polling that drives the ingestion worker.
 *
 * <p>Conditional on the same property as the worker itself, so a test or a
 * maintenance run can disable background processing without leaving a scheduled
 * task behind that does nothing.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "rag.ingestion.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfiguration {}
