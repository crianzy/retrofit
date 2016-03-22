/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit;

import android.os.Build;
import android.os.Process;
import com.google.gson.Gson;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import retrofit.android.AndroidApacheClient;
import retrofit.android.AndroidLog;
import retrofit.android.MainThreadExecutor;
import retrofit.appengine.UrlFetchClient;
import retrofit.client.Client;
import retrofit.client.OkClient;
import retrofit.client.UrlConnectionClient;
import retrofit.converter.Converter;
import retrofit.converter.GsonConverter;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static java.lang.Thread.MIN_PRIORITY;

/**
 * 平台相关的 辅助类
 */
abstract class Platform {
    private static final Platform PLATFORM = findPlatform();

    static final boolean HAS_RX_JAVA = hasRxJavaOnClasspath();

    static Platform get() {
        return PLATFORM;
    }

    /**
     * 寻找当前 是什么平台
     *
     * 多数情况 我们获取的是 Android 平台
     * @return
     */
    private static Platform findPlatform() {
        try {
            // 先实例化一个 Build 看是否抛出异常
            // 不抛出异常 再去获取版本
            Class.forName("android.os.Build");
            if (Build.VERSION.SDK_INT != 0) {
                // 如果版本不为零 则enw 一个 Android 的平台信息
                return new Android();
            }
        } catch (ClassNotFoundException ignored) {
        }

        if (System.getProperty("com.google.appengine.runtime.version") != null) {
            return new AppEngine();
        }

        return new Base();
    }

    abstract Converter defaultConverter();

    abstract Client.Provider defaultClient();

    abstract Executor defaultHttpExecutor();

    abstract Executor defaultCallbackExecutor();

    abstract RestAdapter.Log defaultLog();

    /**
     * Provides sane defaults for operation on the JVM.
     */
    private static class Base extends Platform {
        @Override
        Converter defaultConverter() {
            return new GsonConverter(new Gson());
        }

        @Override
        Client.Provider defaultClient() {
            final Client client;
            if (hasOkHttpOnClasspath()) {
                client = OkClientInstantiator.instantiate();
            } else {
                client = new UrlConnectionClient();
            }
            return new Client.Provider() {
                @Override
                public Client get() {
                    return client;
                }
            };
        }

        @Override
        Executor defaultHttpExecutor() {
            return Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable r) {
                    return new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Thread.currentThread().setPriority(MIN_PRIORITY);
                            r.run();
                        }
                    }, RestAdapter.IDLE_THREAD_NAME);
                }
            });
        }

        @Override
        Executor defaultCallbackExecutor() {
            return new Utils.SynchronousExecutor();
        }

        @Override
        RestAdapter.Log defaultLog() {
            return new RestAdapter.Log() {
                @Override
                public void log(String message) {
                    System.out.println(message);
                }
            };
        }
    }

    /**
     * Provides sane defaults for operation on Android.
     * 向Android 平台 提供一些默认的参数,
     */
    private static class Android extends Platform {
        @Override
        Converter defaultConverter() {
            // 默认使用 Gson 的一个转换器
            return new GsonConverter(new Gson());
        }

        @Override
        Client.Provider defaultClient() {
            final Client client;
            if (hasOkHttpOnClasspath()) {
                // 有 okHttp 则使用 OkHttp
                client = OkClientInstantiator.instantiate();
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
                // 如果版本 小于 9 的话 使用 AndroidApacheClient
                client = new AndroidApacheClient();
            } else {
                // 否则 使用 基于HttpURLConnection 的一个基本封装
                client = new UrlConnectionClient();
            }
            return new Client.Provider() {
                @Override
                public Client get() {
                    return client;
                }
            };
        }

        /**
         * 获取默认的 Http 线程池
         */
        @Override
        Executor defaultHttpExecutor() {
            return Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable r) {
                    return new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // 设置线程 权重
                            Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND);
                            r.run();
                        }
                    }, RestAdapter.IDLE_THREAD_NAME);
                    // 第二个参数 是线程名字
                }
            });
        }

        /**
         * 获取主线程 Handelr
         * @return
         */
        @Override
        Executor defaultCallbackExecutor() {
            return new MainThreadExecutor();
        }

        /**
         * 获取默认的 日志  Android本身的 Log
         * @return
         */
        @Override
        RestAdapter.Log defaultLog() {
            return new AndroidLog("Retrofit");
        }
    }

    private static class AppEngine extends Base {
        @Override
        Client.Provider defaultClient() {
            final UrlFetchClient client = new UrlFetchClient();
            return new Client.Provider() {
                @Override
                public Client get() {
                    return client;
                }
            };
        }
    }

    /**
     * Determine whether or not OkHttp 1.6 or newer is present on the runtime classpath.
     * <p>
     * 判断是否有OkHttp
     */
    private static boolean hasOkHttpOnClasspath() {
        try {
            Class.forName("com.squareup.okhttp.OkHttpClient");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        return false;
    }

    /**
     * Indirection for OkHttp class to prevent VerifyErrors on Android 2.0 and earlier when the
     * dependency is not present.
     */
    private static class OkClientInstantiator {
        static Client instantiate() {
            return new OkClient();
        }
    }

    private static boolean hasRxJavaOnClasspath() {
        try {
            Class.forName("rx.Observable");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        return false;
    }
}
