package com.inboxintelligence.ingester.persistence.storage;

import com.inboxintelligence.ingester.config.EmailStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/** Selects the active EmailStorageProvider based on configuration. */
@Component
@Slf4j
public class EmailStorageProviderFactory {

    private static final String DEFAULT_PROVIDER_BEAN = "localEmailStorageProvider";

    private final EmailStorageProvider activeEmailStorageProvider;

    public EmailStorageProviderFactory(EmailStorageProperties emailStorageProperties,
                                       Map<String, EmailStorageProvider> emailStorageProviderBeanMap) {

        String configuredProvider = emailStorageProperties.provider();
        String beanName = DEFAULT_PROVIDER_BEAN;

        if (StringUtils.hasText(configuredProvider)) {
            beanName = configuredProvider.toLowerCase(Locale.ROOT) + "EmailStorageProvider";
        }

        EmailStorageProvider provider = emailStorageProviderBeanMap.get(beanName);

        if (provider == null) {
            log.warn("Email storage provider '{}' not found. Falling back to LOCAL provider.", beanName);
            provider = emailStorageProviderBeanMap.get(DEFAULT_PROVIDER_BEAN);
        }

        this.activeEmailStorageProvider = provider;

        log.info("Active EmailStorageProvider: {}", provider.getClass().getSimpleName());
    }

    public EmailStorageProvider getProvider() {
        return activeEmailStorageProvider;
    }
}