import type { ErrorHandlerCallback } from 'react-native';
import { parse as parseStack } from 'stacktrace-parser';

import AppMetrics from './module';

let installed = false;

/**
 * Installs a handler for unhandled JavaScript errors by wrapping React Native's
 * `global.ErrorUtils` global handler. The error is reported to the native module (recorded as an
 * `expo.error.uncaught` log event), then the previously-installed handler runs so React Native's
 * default behavior (red box in development, fatal termination in production) is unchanged.
 *
 * Idempotent: only the first call installs. Called automatically when `expo-app-metrics` is
 * imported, so capture is live as early as the app pulls the module in.
 */
export function installErrorHandler(): void {
  if (installed) {
    return;
  }
  // `ErrorUtils` is a React Native global; it doesn't exist on web or before the runtime sets it up.
  if (typeof ErrorUtils === 'undefined') {
    return;
  }
  installed = true;

  const previousHandler = ErrorUtils.getGlobalHandler?.();
  const handler: ErrorHandlerCallback = (error, isFatal) => {
    // Parse on the JS side with the same parser React Native and Metro use, so native receives an
    // engine-agnostic, arrayized stack instead of an engine-specific raw string.
    const stack = (error?.stack ? parseStack(error.stack) : []).map((frame) => ({
      methodName: frame.methodName,
      file: frame.file,
      lineNumber: frame.lineNumber,
      column: frame.column,
    }));
    AppMetrics.reportError({
      name: error?.name,
      message: error?.message ?? String(error),
      stack,
      isFatal: isFatal ?? false,
    });
    previousHandler?.(error, isFatal);
  };
  ErrorUtils.setGlobalHandler(handler);
}
