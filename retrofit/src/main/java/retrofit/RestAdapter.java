/*
 * Copyright (C) 2012 Square, Inc.
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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import retrofit.Profiler.RequestInformation;
import retrofit.client.Client;
import retrofit.client.Header;
import retrofit.client.Request;
import retrofit.client.Response;
import retrofit.converter.ConversionException;
import retrofit.converter.Converter;
import retrofit.mime.MimeUtil;
import retrofit.mime.TypedByteArray;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedOutput;

/**
 * Adapts a Java interface to a REST API.
 * <p>
 * API endpoints are defined as methods on an interface with annotations providing metadata about
 * the form in which the HTTP call should be made.
 * <p>
 * The relative path for a given method is obtained from an annotation on the method describing
 * the request type. The built-in methods are {@link retrofit.http.GET GET},
 * {@link retrofit.http.PUT PUT}, {@link retrofit.http.POST POST}, {@link retrofit.http.HEAD HEAD},
 * and {@link retrofit.http.DELETE DELETE}. You can define your own HTTP method by creating an
 * annotation that takes a {code String} value and itself is annotated with
 * {@link retrofit.http.RestMethod @RestMethod}.
 * <p>
 * Method parameters can be used to replace parts of the URL by annotating them with
 * {@link retrofit.http.Path @Path}. Replacement sections are denoted by an identifier surrounded
 * by curly braces (e.g., "{foo}"). To add items to the query string of a URL use
 * {@link retrofit.http.Query @Query}.
 * <p>
 * HTTP requests happen in one of two ways:
 * <ul>
 * <li>On the provided HTTP {@link Executor} with callbacks marshaled to the callback
 * {@link Executor}. The last method parameter should be of type {@link Callback}. The HTTP
 * response will be converted to the callback's parameter type using the specified
 * {@link retrofit.converter.Converter Converter}. If the callback parameter type uses a wildcard,
 * the lower bound will be used as the conversion type.
 * <li>On the current thread returning the response or throwing a {@link RetrofitError}. The HTTP
 * response will be converted to the method's return type using the specified
 * {@link retrofit.converter.Converter Converter}.
 * </ul>
 * <p>
 * The body of a request is denoted by the {@link retrofit.http.Body @Body} annotation. The object
 * will be converted to request representation by a call to
 * {@link retrofit.converter.Converter#toBody(Object) toBody} on the supplied
 * {@link retrofit.converter.Converter Converter} for this instance. The body can also be a
 * {@link TypedOutput} where it will be used directly.
 * <p>
 * Alternative request body formats are supported by method annotations and corresponding parameter
 * annotations:
 * <ul>
 * <li>{@link retrofit.http.FormUrlEncoded @FormUrlEncoded} - Form-encoded data with key-value
 * pairs specified by the {@link retrofit.http.Field @Field} parameter annotation.
 * <li>{@link retrofit.http.Multipart @Multipart} - RFC 2387-compliant multi-part data with parts
 * specified by the {@link retrofit.http.Part @Part} parameter annotation.
 * </ul>
 * <p>
 * Additional static headers can be added for an endpoint using the
 * {@link retrofit.http.Headers @Headers} method annotation. For per-request control over a header
 * annotate a parameter with {@link Header @Header}.
 * <p>
 * For example:
 * <pre>
 * public interface MyApi {
 *   &#64;POST("/category/{cat}") // Asynchronous execution.
 *   void categoryList(@Path("cat") String a, @Query("page") int b,
 *                     Callback&lt;List&lt;Item&gt;&gt; cb);
 *   &#64;POST("/category/{cat}") // Synchronous execution.
 *   List&lt;Item&gt; categoryList(@Path("cat") String a, @Query("page") int b);
 * }
 * </pre>
 * <p>
 * Calling {@link #create(Class)} with {@code MyApi.class} will validate and create a new
 * implementation of the API.
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Jake Wharton (jw@squareup.com)
 *
 *
 *
 *
 *
 * 看完代码后发现  Retrofit 没有做任务 关于请求的事情
 *
 * 所有的处理 知识为了 更方便的请求 更加灵活的控制请求 前的一些东西
 *
 * 和请求后 返回的结果
 */
public class RestAdapter {
    static final String THREAD_PREFIX = "Retrofit-";
    static final String IDLE_THREAD_NAME = THREAD_PREFIX + "Idle";

    /**
     * Simple logging abstraction for debug messages.
     * <p>
     * Log 日志相关的东西
     */
    public interface Log {
        /**
         * Log a debug message to the appropriate console.
         */
        void log(String message);

        /**
         * A {@link Log} implementation which does not log anything.
         */
        Log NONE = new Log() {
            @Override
            public void log(String message) {
            }
        };
    }

    /**
     * Controls the level of logging.
     */
    public enum LogLevel {
        /**
         * No logging.
         */
        NONE,
        /**
         * Log only the request method and URL and the response status code and execution time.
         */
        BASIC,
        /**
         * Log the basic information along with request and response headers.
         */
        HEADERS,
        /**
         * Log the basic information along with request and response objects via toString().
         */
        HEADERS_AND_ARGS,
        /**
         * Log the headers, body, and metadata for both requests and responses.
         * <p>
         * Note: This requires that the entire request and response body be buffered in memory!
         */
        FULL;

        public boolean log() {
            return this != NONE;
        }
    }

    /**
     * 我们定义的 api 接口    对应 接口中的方法 和方法 一些注解信息什么的
     */
    private final Map<Class<?>, Map<Method, RestMethodInfo>> serviceMethodInfoCache =
            new LinkedHashMap<Class<?>, Map<Method, RestMethodInfo>>();

    final Endpoint server;
    final Executor httpExecutor;
    final Executor callbackExecutor;
    final RequestInterceptor requestInterceptor;
    final Converter converter;
    final Log log;
    final ErrorHandler errorHandler;

    private final Client.Provider clientProvider;
    private final Profiler profiler;
    private RxSupport rxSupport;

    volatile LogLevel logLevel;

    private RestAdapter(Endpoint server, Client.Provider clientProvider, Executor httpExecutor,
                        Executor callbackExecutor, RequestInterceptor requestInterceptor, Converter converter,
                        Profiler profiler, ErrorHandler errorHandler, Log log, LogLevel logLevel) {
        this.server = server;
        this.clientProvider = clientProvider;
        this.httpExecutor = httpExecutor;
        this.callbackExecutor = callbackExecutor;
        this.requestInterceptor = requestInterceptor;
        this.converter = converter;
        this.profiler = profiler;
        this.errorHandler = errorHandler;
        this.log = log;
        this.logLevel = logLevel;
    }

    /**
     * Change the level of logging.
     */
    public void setLogLevel(LogLevel loglevel) {
        if (logLevel == null) {
            throw new NullPointerException("Log level may not be null.");
        }
        this.logLevel = loglevel;
    }

    /**
     * The current logging level.
     */
    public LogLevel getLogLevel() {
        return logLevel;
    }

    /**
     * Create an implementation of the API defined by the specified {@code service} interface.
     * <p>
     * 更具 我们定义的接口 创建出 动态代理的 实例
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> service) {
        Utils.validateServiceClass(service);
        return (T) Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service},
                new RestHandler(getMethodInfoCache(service)));
    }

    Map<Method, RestMethodInfo> getMethodInfoCache(Class<?> service) {
        synchronized (serviceMethodInfoCache) {
            Map<Method, RestMethodInfo> methodInfoCache = serviceMethodInfoCache.get(service);
            if (methodInfoCache == null) {
                methodInfoCache = new LinkedHashMap<Method, RestMethodInfo>();
                serviceMethodInfoCache.put(service, methodInfoCache);
            }
            return methodInfoCache;
        }
    }

    /**
     * 更具 方法  通过解析 注解 参数 获取 方法中的请求信息
     *
     * @param cache
     * @param method
     * @return
     */
    static RestMethodInfo getMethodInfo(Map<Method, RestMethodInfo> cache, Method method) {
        synchronized (cache) {
            RestMethodInfo methodInfo = cache.get(method);
            if (methodInfo == null) {
                methodInfo = new RestMethodInfo(method);
                cache.put(method, methodInfo);
            }
            return methodInfo;
        }
    }

    /**
     * 这个是 动态代理 时  方法 执行的 Headler
     * <p>
     * 继承自 InvocationHandler
     */
    private class RestHandler implements InvocationHandler {
        private final Map<Method, RestMethodInfo> methodDetailsCache;

        RestHandler(Map<Method, RestMethodInfo> methodDetailsCache) {
            this.methodDetailsCache = methodDetailsCache;
        }

        @SuppressWarnings("unchecked") //
        @Override
        public Object invoke(Object proxy, Method method, final Object[] args)
                throws Throwable {
            // If the method is a method from Object then defer to normal invocation.
            if (method.getDeclaringClass() == Object.class) {
                // 如果是 Obj 的方法
                return method.invoke(this, args);
            }

            // Load or create the details cache for the current method.
            // 更具方法 获取 RestMethodInfo 请求信息
            final RestMethodInfo methodInfo = getMethodInfo(methodDetailsCache, method);

            if (methodInfo.isSynchronous) {
                // 如果是同步的话
                try {
                    // 直接执行  并返回数据
                    return invokeRequest(requestInterceptor, methodInfo, args);
                } catch (RetrofitError error) {
                    // 处理跑出来的异常
                    Throwable newError = errorHandler.handleError(error);
                    if (newError == null) {
                        throw new IllegalStateException("Error handler returned null for wrapped exception.",
                                error);
                    }
                    // 处理之后 异常 也需要 跑出去
                    throw newError;
                }
            }

            // 走到这里的都不是 同步
            if (httpExecutor == null || callbackExecutor == null) {
                // 检查线程池
                throw new IllegalStateException("Asynchronous invocation requires calling setExecutors.");
            }

            if (methodInfo.isObservable) {
                // rxJava
                if (rxSupport == null) {
                    if (Platform.HAS_RX_JAVA) {
                        rxSupport = new RxSupport(httpExecutor, errorHandler, requestInterceptor);
                    } else {
                        throw new IllegalStateException("Observable method found but no RxJava on classpath.");
                    }
                }
                // 返回 一个  Observable
                return rxSupport.createRequestObservable(new RxSupport.Invoker() {
                    @Override
                    public ResponseWrapper invoke(RequestInterceptor requestInterceptor) {
                        // 执行 请求的回调接口
                        return (ResponseWrapper) invokeRequest(requestInterceptor, methodInfo, args);
                    }
                });
            }

            // Apply the interceptor synchronously, recording the interception so we can replay it later.
            // This way we still defer argument serialization to the background thread.

            // 这个 既是一个 拦截器
            // 同时 还是一个 拦截方法参数
            final RequestInterceptorTape interceptorTape = new RequestInterceptorTape();

            // 执行拦截
            // 拦截的时候  主要 就是 在 往 对象中注入数据
            // 这里的拦截 有点意思
            // 这个方法 就是我们 在 build 的时候 毁掉那个的那么拦截  但是上爱你的那么不是
            // 但是传递的参数是
            requestInterceptor.intercept(interceptorTape);

            // 获取CallBack 对象
            Callback<?> callback = (Callback<?>) args[args.length - 1];
            httpExecutor.execute(new CallbackRunnable(callback, callbackExecutor, errorHandler) {
                @Override
                public ResponseWrapper obtainResponse() {
                    // 指向 方法
                    return (ResponseWrapper) invokeRequest(interceptorTape, methodInfo, args);
                }
            });
            return null; // Asynchronous methods should have return type of void.
        }


        /**
         * Execute an HTTP request.
         * 执行 http 请求
         *
         * @param requestInterceptor  这个拦截器 是一个
         * @param methodInfo         请求信息
         * @param args               方法参数
         * @return HTTP response object of specified {@code type} or {@code null}.
         * @throws RetrofitError if any error occurs during the HTTP request.
         */
        private Object invokeRequest(RequestInterceptor requestInterceptor, RestMethodInfo methodInfo,
                                     Object[] args) {
            String url = null;
            try {
                // 请求信息初始化, 保证方法上的额灭个 注解都没加载过
                methodInfo.init(); // Ensure all relevant method information has been loaded.

                // base URL
                String serverUrl = server.getUrl();
                // 构建一个 请求 Builder
                RequestBuilder requestBuilder = new RequestBuilder(serverUrl, methodInfo, converter);
                // 设置参数  读取参数注解
                requestBuilder.setArguments(args);

                // 这个拦截器, 不是我们自己定义的,  这个是内部的拦截的
                requestInterceptor.intercept(requestBuilder);

                // 构建 Request 请求
                Request request = requestBuilder.build();
                url = request.getUrl();

                if (!methodInfo.isSynchronous) {
                    // 不是同步的话
                    // If we are executing asynchronously then update the current thread with a useful name.
                    int substrEnd = url.indexOf("?", serverUrl.length());
                    if (substrEnd == -1) {
                        substrEnd = url.length();
                    }
                    // 设置 当前线程的名字
                    Thread.currentThread().setName(THREAD_PREFIX
                            + url.substring(serverUrl.length(), substrEnd));
                }

                if (logLevel.log()) {
                    // Log the request data.
                    // 打印 请求日志
                    request = logAndReplaceRequest("HTTP", request, args);
                }

                Object profilerObject = null;
                if (profiler != null) {
                    // 额 profiler 是没有默认值的 没设置的就是null
                    // 回调 profiler 开始 请求
                    profilerObject = profiler.beforeCall();
                }

                long start = System.nanoTime();
                // 执行请求  这一步 就是 http 请求 完全 交给了  clientProvider 来处理
                Response response = clientProvider.get().execute(request);
                // 请求花费的事件
                long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                // 状态吗
                int statusCode = response.getStatus();
                if (profiler != null) {
                    RequestInformation requestInfo = getRequestInfo(serverUrl, methodInfo, request);
                    //noinspection unchecked
                    profiler.afterCall(requestInfo, elapsedTime, statusCode, profilerObject);
                }

                if (logLevel.log()) {
                    // Log the response data.
                    response = logAndReplaceResponse(url, response, elapsedTime);
                }

                // 获取接口方法中 定义的返回雷系
                Type type = methodInfo.responseObjectType;

                if (statusCode >= 200 && statusCode < 300) { // 2XX == successful request
                    // 200 成功
                    // Caller requested the raw Response object directly.
                    if (type.equals(Response.class)) {
                        // 是 Response.class
                        if (!methodInfo.isStreaming) {
                            // 且不是流  则把 response 的body  转成ByteArray
                            // Read the entire stream and replace with one backed by a byte[].
                            response = Utils.readBodyToBytesIfNecessary(response);
                        }

                        if (methodInfo.isSynchronous) {
                            // 如果这里是同步的话  直接 返回
                            return response;
                        }
                        // 不是同步的话 这里 返回一个包装类型
                        return new ResponseWrapper(response, response);
                    }

                    // 获取 body
                    TypedInput body = response.getBody();
                    if (body == null) {
                        // body 为空
                        if (methodInfo.isSynchronous) {
                            return null;
                        }
                        return new ResponseWrapper(response, null);
                    }

                    ExceptionCatchingTypedInput wrapped = new ExceptionCatchingTypedInput(body);
                    try {
                        // 转换器 转换 body 这里可能会报出异常 我们经常用一下 自尽定义的 转换器, 在里面定义了 返回 一些值 表示错误什么的
                        Object convert = converter.fromBody(wrapped, type);
                        logResponseBody(body, convert);
                        if (methodInfo.isSynchronous) {
                            // 同步的话 直接返回
                            return convert;
                        }
                        // 不是同步的话 还得再包装一下
                        return new ResponseWrapper(response, convert);
                    } catch (ConversionException e) {
                        // If the underlying input stream threw an exception, propagate that rather than
                        // indicating that it was a conversion exception.

                        // 如果是底层输入流的话 那么抛出异常
                        if (wrapped.threwException()) {
                            // 判断是否 抛出异常
                            throw wrapped.getThrownException();
                        }

                        // The response body was partially read by the converter. Replace it with null.
                        // 吧原来的 有错误的 respons 置为 null
                        response = Utils.replaceResponseBody(response, null);

                        // 抛出转换 出错一样
                        throw RetrofitError.conversionError(url, response, converter, type, e);
                    }
                }

                response = Utils.readBodyToBytesIfNecessary(response);
                throw RetrofitError.httpError(url, response, converter, type);
            } catch (RetrofitError e) {
                throw e; // Pass through our own errors.
            } catch (IOException e) {
                if (logLevel.log()) {
                    logException(e, url);
                }
                throw RetrofitError.networkError(url, e);
            } catch (Throwable t) {
                if (logLevel.log()) {
                    logException(t, url);
                }
                throw RetrofitError.unexpectedError(url, t);
            } finally {
                if (!methodInfo.isSynchronous) {
                    // 请求结束 修改线程名字
                    Thread.currentThread().setName(IDLE_THREAD_NAME);
                }
            }
        }
    }

    /**
     * Log request headers and body. Consumes request body and returns identical replacement.
     */
    Request logAndReplaceRequest(String name, Request request, Object[] args) throws IOException {
        log.log(String.format("---> %s %s %s", name, request.getMethod(), request.getUrl()));

        if (logLevel.ordinal() >= LogLevel.HEADERS.ordinal()) {
            for (Header header : request.getHeaders()) {
                log.log(header.toString());
            }

            String bodySize = "no";
            TypedOutput body = request.getBody();
            if (body != null) {
                String bodyMime = body.mimeType();
                if (bodyMime != null) {
                    log.log("Content-Type: " + bodyMime);
                }

                long bodyLength = body.length();
                bodySize = bodyLength + "-byte";
                if (bodyLength != -1) {
                    log.log("Content-Length: " + bodyLength);
                }

                if (logLevel.ordinal() >= LogLevel.FULL.ordinal()) {
                    if (!request.getHeaders().isEmpty()) {
                        log.log("");
                    }
                    if (!(body instanceof TypedByteArray)) {
                        // Read the entire response body to we can log it and replace the original response
                        request = Utils.readBodyToBytesIfNecessary(request);
                        body = request.getBody();
                    }

                    byte[] bodyBytes = ((TypedByteArray) body).getBytes();
                    String bodyCharset = MimeUtil.parseCharset(body.mimeType(), "UTF-8");
                    log.log(new String(bodyBytes, bodyCharset));
                } else if (logLevel.ordinal() >= LogLevel.HEADERS_AND_ARGS.ordinal()) {
                    if (!request.getHeaders().isEmpty()) {
                        log.log("---> REQUEST:");
                    }
                    for (int i = 0; i < args.length; i++) {
                        log.log("#" + i + ": " + args[i]);
                    }
                }
            }

            log.log(String.format("---> END %s (%s body)", name, bodySize));
        }

        return request;
    }

    /**
     * Log response headers and body. Consumes response body and returns identical replacement.
     */
    private Response logAndReplaceResponse(String url, Response response, long elapsedTime)
            throws IOException {
        log.log(String.format("<--- HTTP %s %s (%sms)", response.getStatus(), url, elapsedTime));

        if (logLevel.ordinal() >= LogLevel.HEADERS.ordinal()) {
            for (Header header : response.getHeaders()) {
                log.log(header.toString());
            }

            long bodySize = 0;
            TypedInput body = response.getBody();
            if (body != null) {
                bodySize = body.length();

                if (logLevel.ordinal() >= LogLevel.FULL.ordinal()) {
                    if (!response.getHeaders().isEmpty()) {
                        log.log("");
                    }

                    if (!(body instanceof TypedByteArray)) {
                        // Read the entire response body so we can log it and replace the original response
                        response = Utils.readBodyToBytesIfNecessary(response);
                        body = response.getBody();
                    }

                    byte[] bodyBytes = ((TypedByteArray) body).getBytes();
                    bodySize = bodyBytes.length;
                    String bodyMime = body.mimeType();
                    String bodyCharset = MimeUtil.parseCharset(bodyMime, "UTF-8");
                    log.log(new String(bodyBytes, bodyCharset));
                }
            }

            log.log(String.format("<--- END HTTP (%s-byte body)", bodySize));
        }

        return response;
    }

    private void logResponseBody(TypedInput body, Object convert) {
        if (logLevel.ordinal() == LogLevel.HEADERS_AND_ARGS.ordinal()) {
            log.log("<--- BODY:");
            log.log(convert.toString());
        }
    }

    /**
     * Log an exception that occurred during the processing of a request or response.
     */
    void logException(Throwable t, String url) {
        log.log(String.format("---- ERROR %s", url != null ? url : ""));
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log.log(sw.toString());
        log.log("---- END ERROR");
    }

    /**
     * 获取 请求 request 请求信息
     *
     * @param serverUrl
     * @param methodDetails
     * @param request
     * @return
     */
    private static Profiler.RequestInformation getRequestInfo(String serverUrl,
                                                              RestMethodInfo methodDetails, Request request) {
        long contentLength = 0;
        String contentType = null;

        TypedOutput body = request.getBody();
        if (body != null) {
            contentLength = body.length();
            contentType = body.mimeType();
        }

        return new Profiler.RequestInformation(methodDetails.requestMethod, serverUrl,
                methodDetails.requestUrl, contentLength, contentType);
    }

    /**
     * Build a new {@link RestAdapter}.
     * <p>
     * Calling the following methods is required before calling {@link #build()}:
     * <ul>
     * <li>{@link #setEndpoint(Endpoint)}</li>
     * <li>{@link #setClient(Client.Provider)}</li>
     * <li>{@link #setConverter(Converter)}</li>
     * </ul>
     * <p>
     * If you are using asynchronous execution (i.e., with {@link Callback Callbacks}) the following
     * is also required:
     * <ul>
     * <li>{@link #setExecutors(java.util.concurrent.Executor, java.util.concurrent.Executor)}</li>
     * </ul>
     * <p>
     * buidl 模式 构建一个 RestAdapter
     */
    public static class Builder {
        private Endpoint endpoint;
        private Client.Provider clientProvider;
        private Executor httpExecutor;
        private Executor callbackExecutor;
        private RequestInterceptor requestInterceptor;
        private Converter converter;
        private Profiler profiler;
        private ErrorHandler errorHandler;
        private Log log;
        private LogLevel logLevel = LogLevel.NONE;

        /**
         * API endpoint URL.
         * <p>
         * 设置 BaseURL 地址
         */
        public Builder setEndpoint(String endpoint) {
            if (endpoint == null || endpoint.trim().length() == 0) {
                throw new NullPointerException("Endpoint may not be blank.");
            }
            this.endpoint = Endpoints.newFixedEndpoint(endpoint);
            return this;
        }

        /**
         * API endpoint.
         * 设置 BaseURL 地址
         * Endpoint 也就是封装了 url 和name
         */
        public Builder setEndpoint(Endpoint endpoint) {
            if (endpoint == null) {
                throw new NullPointerException("Endpoint may not be null.");
            }
            this.endpoint = endpoint;
            return this;
        }

        /**
         * The HTTP client used for requests.
         * 设置 http 请求的客户端  httpok  等
         */
        public Builder setClient(final Client client) {
            if (client == null) {
                throw new NullPointerException("Client may not be null.");
            }
            return setClient(new Client.Provider() {
                @Override
                public Client get() {
                    return client;
                }
            });
        }

        /**
         * The HTTP client used for requests.
         * 设置 http 请求的客户端
         */
        public Builder setClient(Client.Provider clientProvider) {
            if (clientProvider == null) {
                throw new NullPointerException("Client provider may not be null.");
            }
            this.clientProvider = clientProvider;
            return this;
        }

        /**
         * Executors used for asynchronous HTTP client downloads and callbacks.
         * <p>
         * 设置 Http 请求的线程池
         *
         * @param httpExecutor     Executor on which HTTP client calls will be made.
         * @param callbackExecutor Executor on which any {@link Callback} methods will be invoked. If
         *                         this argument is {@code null} then callback methods will be run on the same thread as the
         *                         HTTP client.
         */
        public Builder setExecutors(Executor httpExecutor, Executor callbackExecutor) {
            if (httpExecutor == null) {
                throw new NullPointerException("HTTP executor may not be null.");
            }
            if (callbackExecutor == null) {
                callbackExecutor = new Utils.SynchronousExecutor();
            }
            this.httpExecutor = httpExecutor;
            this.callbackExecutor = callbackExecutor;
            return this;
        }

        /**
         * A request interceptor for adding data to every request.
         * <p>
         * 设置 请求 拦截 是请求前的拦截 可以在这里 加入一些公共的参数
         */
        public Builder setRequestInterceptor(RequestInterceptor requestInterceptor) {
            if (requestInterceptor == null) {
                throw new NullPointerException("Request interceptor may not be null.");
            }
            this.requestInterceptor = requestInterceptor;
            return this;
        }

        /**
         * The converter used for serialization and deserialization of objects.
         * <p>
         * 转换器 ,  用于 序列化 和 反序列化
         * <p>
         * 额  其实就是用于 流 转 json什么的
         * 用 json  gosn  什么的
         */
        public Builder setConverter(Converter converter) {
            if (converter == null) {
                throw new NullPointerException("Converter may not be null.");
            }
            this.converter = converter;
            return this;
        }

        /**
         * Set the profiler used to measure requests.
         * <p>
         * 设置分析器  用于 测量请求
         */
        public Builder setProfiler(Profiler profiler) {
            if (profiler == null) {
                throw new NullPointerException("Profiler may not be null.");
            }
            this.profiler = profiler;
            return this;
        }

        /**
         * The error handler allows you to customize the type of exception thrown for errors on
         * synchronous requests.
         * <p>
         * 设置 异常 处理器
         */
        public Builder setErrorHandler(ErrorHandler errorHandler) {
            if (errorHandler == null) {
                throw new NullPointerException("Error handler may not be null.");
            }
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Configure debug logging mechanism.
         * <p>
         * 设置日志
         */
        public Builder setLog(Log log) {
            if (log == null) {
                throw new NullPointerException("Log may not be null.");
            }
            this.log = log;
            return this;
        }

        /**
         * Change the level of logging.
         * <p>
         * 设置日志级别
         */
        public Builder setLogLevel(LogLevel logLevel) {
            if (logLevel == null) {
                throw new NullPointerException("Log level may not be null.");
            }
            this.logLevel = logLevel;
            return this;
        }

        /**
         * Create the {@link RestAdapter} instances.
         * 构建
         */
        public RestAdapter build() {
            if (endpoint == null) {
                throw new IllegalArgumentException("Endpoint may not be null.");
            }
            ensureSaneDefaults();
            // new 出 RestAdapter
            return new RestAdapter(endpoint, clientProvider, httpExecutor, callbackExecutor,
                    requestInterceptor, converter, profiler, errorHandler, log, logLevel);
        }

        /**
         * 设置默认值
         */
        private void ensureSaneDefaults() {
            if (converter == null) {
                converter = Platform.get().defaultConverter();
            }
            if (clientProvider == null) {
                clientProvider = Platform.get().defaultClient();
            }
            if (httpExecutor == null) {
                httpExecutor = Platform.get().defaultHttpExecutor();
            }
            if (callbackExecutor == null) {
                callbackExecutor = Platform.get().defaultCallbackExecutor();
            }
            if (errorHandler == null) {
                errorHandler = ErrorHandler.DEFAULT;
            }
            if (log == null) {
                log = Platform.get().defaultLog();
            }
            if (requestInterceptor == null) {
                requestInterceptor = RequestInterceptor.NONE;
            }
        }
    }
}
