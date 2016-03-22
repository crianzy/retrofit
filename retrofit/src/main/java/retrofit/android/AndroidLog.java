package retrofit.android;

import android.util.Log;
import retrofit.RestAdapter;

/** A {@link RestAdapter.Log logger} for Android.
 *
 * 对 Android Log 的一恶习封装
 * */
public class AndroidLog implements RestAdapter.Log {
  private static final int LOG_CHUNK_SIZE = 4000;

  private final String tag;

  public AndroidLog(String tag) {
    this.tag = tag;
  }

  @Override public final void log(String message) {
    for (int i = 0, len = message.length(); i < len; i += LOG_CHUNK_SIZE) {
      // 这里现在 一次 打印日志 的长度
      int end = Math.min(len, i + LOG_CHUNK_SIZE);
      logChunk(message.substring(i, end));
    }
  }

  /**
   * Called one or more times for each call to {@link #log(String)}. The length of {@code chunk}
   * will be no more than 4000 characters to support Android's {@link Log} class.
   */
  public void logChunk(String chunk) {
    Log.d(getTag(), chunk);
  }

  public String getTag() {
    return tag;
  }
}
