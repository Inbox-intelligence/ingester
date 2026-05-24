package com.inboxintelligence.ingester.domain.label;

import lombok.experimental.UtilityClass;

@UtilityClass
class LabelNameUtils {

    String extractDisplayName(String fullName) {
        int lastSlash = fullName.lastIndexOf('/');
        return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
    }
}
