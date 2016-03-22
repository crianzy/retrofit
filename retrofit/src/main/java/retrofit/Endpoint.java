package retrofit;

/**
 * Represents an API endpoint URL and associated name. Callers should always consult the instance
 * for the latest values rather than caching the returned values.
 *
 * base URL 地址
 *
 * @author Matt Hickman (mhickman@palantir.com)
 */
public interface Endpoint {

  /** The base API URL. */
  String getUrl();

  /** A name for differentiating between multiple API URLs.
   *
   * 名字, 用于却分多个 API URLs
   * */
  String getName();

}
