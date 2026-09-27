export type ErrorCode =
  | 'unauthorized'
  | 'forbidden'
  | 'not_found'
  | 'validation'
  | 'conflict'
  | 'rate_limited'
  | 'feature_unavailable'
  | 'actual_unavailable'
  | 'internal';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ErrorCode,
    message: string,
    readonly detail?: string,
  ) {
    super(message);
  }

  static unauthorized(detail?: string) {
    return new ApiError(401, 'unauthorized', 'Unauthorized', detail);
  }
  static forbidden(detail?: string) {
    return new ApiError(403, 'forbidden', 'Forbidden', detail);
  }
  static notFound(detail?: string) {
    return new ApiError(404, 'not_found', 'Not found', detail);
  }
  static validation(detail: string) {
    return new ApiError(400, 'validation', 'Invalid request', detail);
  }
  static featureUnavailable(feature: string) {
    return new ApiError(501, 'feature_unavailable', 'Feature unavailable', `"${feature}" is not supported by this Actual version`);
  }
  static actualUnavailable(detail?: string) {
    return new ApiError(503, 'actual_unavailable', 'Actual server unavailable', detail);
  }
}
