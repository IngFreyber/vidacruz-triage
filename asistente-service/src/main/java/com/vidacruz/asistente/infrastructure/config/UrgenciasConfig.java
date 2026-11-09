package com.vidacruz.asistente.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VistaUrgenciasProperties.class)
public class UrgenciasConfig {
}
