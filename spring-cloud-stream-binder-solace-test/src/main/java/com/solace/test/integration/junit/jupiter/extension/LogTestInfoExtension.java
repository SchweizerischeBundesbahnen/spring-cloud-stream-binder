package com.solace.test.integration.junit.jupiter.extension;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class LogTestInfoExtension implements AfterEachCallback, BeforeEachCallback {
	private static final String DISPLAY_NAME_CLASS_DELIMITER = ".";
	private static final String DISPLAY_NAME_DELIMITER = " - ";
	private static final Logger LOGGER = LoggerFactory.getLogger(LogTestInfoExtension.class);

	@Override
	public void afterEach(ExtensionContext context) {
		log(context, "Ended Test");
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		log(context, "Starting Test");
	}

	private void log(ExtensionContext context, String label) {
		StringBuilder displayName = new StringBuilder(context.getDisplayName());
		for (Optional<ExtensionContext> parentContext = context.getParent();
			 parentContext.isPresent() && !parentContext.get().equals(context.getRoot());
			 parentContext = parentContext.get().getParent()) {
			boolean isClassContext = parentContext.get().getElement().map(e -> e instanceof Class).orElse(false);
			displayName.insert(0, isClassContext ? DISPLAY_NAME_CLASS_DELIMITER : DISPLAY_NAME_DELIMITER)
					.insert(0, parentContext.get().getDisplayName());
		}

		context.getTestClass()
				.map(LoggerFactory::getLogger)
				.orElse(LOGGER)
				.info("{}: {}", label, displayName);
	}
}
