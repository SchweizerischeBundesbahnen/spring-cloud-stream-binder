package com.solace.test.integration.junit.jupiter.extension;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>JUnit 5 extension for capturing logs (currently only supports capturing from Log4J).</p>
 * <p><b>Usage:</b></p>
 * <pre><code>
 *	{@literal @}ExtendWith(LogCaptorExtension.class)
 *	public class Test {
 *		{@literal @}Test
 *		public void testMethod({@literal @}LogCaptor(ClazzToCapture.class) BufferedReader logReader) {
 *			// Test logic
 *  	}
 *  }
 * </code></pre>
 */
public class LogCaptorExtension implements ParameterResolver {
	private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(LogCaptorExtension.class);
	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(LogCaptorExtension.class);

	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		return BufferedReader.class.isAssignableFrom(parameterContext.getParameter().getType())
				&& parameterContext.isAnnotated(LogCaptor.class);
	}

	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		return extensionContext.getStore(NAMESPACE).getOrComputeIfAbsent(LogCaptorResource.class, c -> {
			Logger logger = (Logger) LogManager.getLogger(
					parameterContext.findAnnotation(LogCaptor.class)
							.map(LogCaptor::value)
							.orElseThrow(() -> new ParameterResolutionException(String.format(
									"parameter %s is not annotated with %s",
									parameterContext.getParameter().getName(), LogCaptor.class))));
			PipedReader in = new PipedReader();
			Appender appender;
			try {
				appender = WriterAppender.newBuilder()
						.setName(logger.getName() + "-LogCaptor")
						.setTarget(new PipedWriter(in))
						.setLayout(PatternLayout.createDefaultLayout())
						.build();
			} catch (IOException e) {
				throw new ParameterResolutionException("Failed to create log writer", e);
			}
			appender.start();
			LOG.info("Adding appender {} to logger {}", appender.getName(), logger.getName());
			logger.addAppender(appender);
			BufferedReader bufferedReader = new BufferedReader(in);
			return new LogCaptorResource(logger, appender, bufferedReader);
		}, LogCaptorResource.class).getBufferedReader();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.PARAMETER)
	public @interface LogCaptor {
		Class<?> value();
	}

	private static final class LogCaptorResource implements ExtensionContext.Store.CloseableResource {
		private final Logger logger;
		private final Appender appender;
		private final BufferedReader bufferedReader;

		private LogCaptorResource(Logger logger, Appender appender, BufferedReader bufferedReader) {
			this.logger = logger;
			this.appender = appender;
			this.bufferedReader = bufferedReader;
		}

		public BufferedReader getBufferedReader() {
			return bufferedReader;
		}

		@Override
		public void close() throws Throwable {
			LOG.info("Removing appender {} from logger {}", appender.getName(), logger.getName());
			logger.removeAppender(appender);
			appender.stop();
			bufferedReader.close();
		}
	}
}
