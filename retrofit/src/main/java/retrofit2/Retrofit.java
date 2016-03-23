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
package retrofit2;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.http.GET;
import retrofit2.http.HTTP;
import retrofit2.http.Header;
import retrofit2.http.Url;

import static java.util.Collections.unmodifiableList;
import static retrofit2.Utils.checkNotNull;

/**
 * Retrofit adapts a Java interface to HTTP calls by using annotations on the declared methods to
 * define how requests are made. Create instances using {@linkplain Builder
 * the builder} and pass your interface to {@link #create} to generate an implementation.
 * <p>
 * For example,
 * <pre>{@code
 * Retrofit retrofit = new Retrofit.Builder()
 *     .baseUrl("https://api.example.com/")
 *     .addConverterFactory(GsonConverterFactory.create())
 *     .build();
 *
 * MyApi api = retrofit.create(MyApi.class);
 * Response<User> user = api.getUser().execute();
 * }</pre>
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Jake Wharton (jw@squareup.com)
 */
public final class Retrofit {
    //  每个 方法 都对应 一个  ServiceMethod 方法处理 服务独享
    private final Map<Method, ServiceMethod> serviceMethodCache = new LinkedHashMap<>();

    // ok Http 客户端
    private final okhttp3.Call.Factory callFactory;
    // baseUrl
    private final HttpUrl baseUrl;
    // 转换器雷彪
    private final List<Converter.Factory> converterFactories;
    // 回调 处理 适配器 列表
    private final List<CallAdapter.Factory> adapterFactories;
    // 回调线程池
    private final Executor callbackExecutor;
    // 是否 Create 创建动态代理 的时候 加载 并缓存 接口方法 校验接口内部方法参数
    private final boolean validateEagerly;

    /**
     * 创建 Retrofit
     *
     * @param callFactory
     * @param baseUrl
     * @param converterFactories
     * @param adapterFactories
     * @param callbackExecutor
     * @param validateEagerly
     */
    Retrofit(okhttp3.Call.Factory callFactory, HttpUrl baseUrl,
             List<Converter.Factory> converterFactories, List<CallAdapter.Factory> adapterFactories,
             Executor callbackExecutor, boolean validateEagerly) {
        this.callFactory = callFactory;
        this.baseUrl = baseUrl;
        this.converterFactories = unmodifiableList(converterFactories); // Defensive copy at call site.
        this.adapterFactories = unmodifiableList(adapterFactories); // Defensive copy at call site.
        this.callbackExecutor = callbackExecutor;
        this.validateEagerly = validateEagerly;
    }

    /**
     * 这里动态代理 创建 接口的动态代理实现
     * <p>
     * Create an implementation of the API endpoints defined by the {@code service} interface.
     * <p>
     * The relative path for a given method is obtained from an annotation on the method describing
     * the request type. The built-in methods are {@link retrofit2.http.GET GET},
     * {@link retrofit2.http.PUT PUT}, {@link retrofit2.http.POST POST}, {@link retrofit2.http.PATCH
     * PATCH}, {@link retrofit2.http.HEAD HEAD}, {@link retrofit2.http.DELETE DELETE} and
     * {@link retrofit2.http.OPTIONS OPTIONS}. You can use a custom HTTP method with
     * {@link HTTP @HTTP}. For a dynamic URL, omit the path on the annotation and annotate the first
     * parameter with {@link Url @Url}.
     * <p>
     * Method parameters can be used to replace parts of the URL by annotating them with
     * {@link retrofit2.http.Path @Path}. Replacement sections are denoted by an identifier
     * surrounded by curly braces (e.g., "{foo}"). To add items to the query string of a URL use
     * {@link retrofit2.http.Query @Query}.
     * <p>
     * The body of a request is denoted by the {@link retrofit2.http.Body @Body} annotation. The
     * object will be converted to request representation by one of the {@link Converter.Factory}
     * instances. A {@link RequestBody} can also be used for a raw representation.
     * <p>
     * Alternative request body formats are supported by method annotations and corresponding
     * parameter annotations:
     * <ul>
     * <li>{@link retrofit2.http.FormUrlEncoded @FormUrlEncoded} - Form-encoded data with key-value
     * pairs specified by the {@link retrofit2.http.Field @Field} parameter annotation.
     * <li>{@link retrofit2.http.Multipart @Multipart} - RFC 2388-compliant multipart data with
     * parts specified by the {@link retrofit2.http.Part @Part} parameter annotation.
     * </ul>
     * <p>
     * Additional static headers can be added for an endpoint using the
     * {@link retrofit2.http.Headers @Headers} method annotation. For per-request control over a
     * header annotate a parameter with {@link Header @Header}.
     * <p>
     * By default, methods return a {@link Call} which represents the HTTP request. The generic
     * parameter of the call is the response body type and will be converted by one of the
     * {@link Converter.Factory} instances. {@link ResponseBody} can also be used for a raw
     * representation. {@link Void} can be used if you do not care about the body contents.
     * <p>
     * For example:
     * <pre>
     * public interface CategoryService {
     *   &#64;POST("category/{cat}/")
     *   Call&lt;List&lt;Item&gt;&gt; categoryList(@Path("cat") String a, @Query("page") int b);
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked") // Single-interface proxy creation guarded by parameter safety.
    public <T> T create(final Class<T> service) {
        // 校验接口,  必须得是 接口 且不能继承其他接口
        Utils.validateServiceInterface(service);
        if (validateEagerly) {
            eagerlyValidateMethods(service);
        }
        // 创建动态代理
        return (T) Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service},
                new InvocationHandler() {
                    private final Platform platform = Platform.get();

                    @Override
                    public Object invoke(Object proxy, Method method, Object... args)
                            throws Throwable {
                        // If the method is a method from Object then defer to normal invocation.
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(this, args);
                        }
                        if (platform.isDefaultMethod(method)) {
                            // 如果是 java8 的Default 方法 则执行 Default 方法
                            return platform.invokeDefaultMethod(method, service, proxy, args);
                        }
                        // 加载 方法 处理对象
                        ServiceMethod serviceMethod = loadServiceMethod(method);
                        OkHttpCall okHttpCall = new OkHttpCall<>(serviceMethod, args);
//                         请求 适配器 的适配 并返回 适配后的结果
//                         这里要注意的是,  这里不像 1.9 版本 分了 同步 和异步
//                         2.0 可以理解为都是同步模式
//                         即 Retrofit 在哪个线程执行, 那么Http 线程就在哪个线程执行
//                         所有需要注意的是是, 如果不用 RxJvaa 的话

//                        如果是异步请求的话 ,需要使用如下方法:
//                        Call<Repo> call = service.loadRepo();
//                        call.enqueue(new Callback<Repo>() {
//                                @Override
//                                public void onResponse(Response<Repo> response) {
//                                        // Get result Repo from response.body()
//                                    }
//                              
//                                @Override
//                                public void onFailure(Throwable t) {
//                                  
//                                    }
//                        });

//                        同步请求如下:
//                        Call<Repo> call = service.loadRepo();
//                        Repo repo = call.execute();

//                         由上面 可以看到  方法的在返回结果的时候 还没有请求
//                         当然 其实可以在 adapt 放纵请求
//                         rxJava 就是这么做的 然后返回一个 Observable

                        // 请求的 在 okHttpCall 对象中完成
                        return serviceMethod.callAdapter.adapt(okHttpCall);
                    }
                });
    }

    /**
     * 校验 方法
     *
     * @param service
     */
    private void eagerlyValidateMethods(Class<?> service) {
        Platform platform = Platform.get();
        for (Method method : service.getDeclaredMethods()) {
            if (!platform.isDefaultMethod(method)) {
                // 不是java 8 的Default 方法
                loadServiceMethod(method);
            }
        }
    }

    /**
     * 加载 接口方法 并缓存
     *
     * @param method
     * @return
     */
    ServiceMethod loadServiceMethod(Method method) {
        ServiceMethod result;
        synchronized (serviceMethodCache) {
            result = serviceMethodCache.get(method);
            if (result == null) {
                // 构建 ServiceMethod 方法封装 对象
                result = new ServiceMethod.Builder(this, method).build();
                serviceMethodCache.put(method, result);
            }
        }
        return result;
    }

    /**
     * The factory used to create {@linkplain okhttp3.Call OkHttp calls} for sending a HTTP requests.
     * Typically an instance of {@link OkHttpClient}.
     */
    public okhttp3.Call.Factory callFactory() {
        return callFactory;
    }

    /**
     * The API base URL.
     */
    public HttpUrl baseUrl() {
        return baseUrl;
    }

    /**
     * Returns a list of the factories tried when creating a
     * {@linkplain #callAdapter(Type, Annotation[])} call adapter}.
     */
    public List<CallAdapter.Factory> callAdapterFactories() {
        return adapterFactories;
    }


    /**
     * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain}
     * #callAdapterFactories() factories
     *
     * @param returnType  方法 返回类型
     * @param annotations 方法 注解
     * @returnIllegalArgumentException if no call adapter available for {@code type}.
     */
    public CallAdapter<?> callAdapter(Type returnType, Annotation[] annotations) {
        return nextCallAdapter(null, returnType, annotations);
    }

    /**
     * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain
     * #callAdapterFactories() factories} except {@code skipPast}.
     *
     * @throws IllegalArgumentException if no call adapter available for {@code type}.
     */

    /**
     * @param skipPast 跳过的位置,  从这个位置之后开始遍历
     */
    public CallAdapter<?> nextCallAdapter(CallAdapter.Factory skipPast, Type returnType,
                                          Annotation[] annotations) {
        checkNotNull(returnType, "returnType == null");
        checkNotNull(annotations, "annotations == null");

        // 确定遍历的开始位置
        int start = adapterFactories.indexOf(skipPast) + 1;
        for (int i = start, count = adapterFactories.size(); i < count; i++) {
            // 通过相应的适配器工厂  获得 相应的 适配器
            CallAdapter<?> adapter = adapterFactories.get(i).get(returnType, annotations, this);
            // 走到 一个就范湖  不在 继续循环
            if (adapter != null) {
                return adapter;
            }
        }
        // 没找到 回到 处理的 适配器 抛出异常

        StringBuilder builder = new StringBuilder("Could not locate call adapter for ")
                .append(returnType)
                .append(".\n");
        if (skipPast != null) {
            builder.append("  Skipped:");
            for (int i = 0; i < start; i++) {
                builder.append("\n   * ").append(adapterFactories.get(i).getClass().getName());
            }
            builder.append('\n');
        }
        builder.append("  Tried:");
        for (int i = start, count = adapterFactories.size(); i < count; i++) {
            builder.append("\n   * ").append(adapterFactories.get(i).getClass().getName());
        }
        throw new IllegalArgumentException(builder.toString());
    }

    /**
     * Returns a list of the factories tried when creating a
     * {@linkplain #requestBodyConverter(Type, Annotation[], Annotation[]) request body converter}, a
     * {@linkplain #responseBodyConverter(Type, Annotation[]) response body converter}, or a
     * {@linkplain #stringConverter(Type, Annotation[]) string converter}.
     */
    public List<Converter.Factory> converterFactories() {
        return converterFactories;
    }

    /**
     * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
     * {@linkplain #converterFactories() factories}.
     *
     * @throws IllegalArgumentException if no converter available for {@code type}.
     */
    public <T> Converter<T, RequestBody> requestBodyConverter(Type type,
                                                              Annotation[] parameterAnnotations, Annotation[] methodAnnotations) {
        return nextRequestBodyConverter(null, type, parameterAnnotations, methodAnnotations);
    }

    /**
     * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
     * {@linkplain #converterFactories() factories} except {@code skipPast}.
     *
     * @throws IllegalArgumentException if no converter available for {@code type}.
     */
    public <T> Converter<T, RequestBody> nextRequestBodyConverter(Converter.Factory skipPast,
                                                                  Type type, Annotation[] parameterAnnotations, Annotation[] methodAnnotations) {
        checkNotNull(type, "type == null");
        checkNotNull(parameterAnnotations, "parameterAnnotations == null");
        checkNotNull(methodAnnotations, "methodAnnotations == null");

        int start = converterFactories.indexOf(skipPast) + 1;
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            Converter.Factory factory = converterFactories.get(i);
            Converter<?, RequestBody> converter =
                    factory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, this);
            if (converter != null) {
                //noinspection unchecked
                return (Converter<T, RequestBody>) converter;
            }
        }

        StringBuilder builder = new StringBuilder("Could not locate RequestBody converter for ")
                .append(type)
                .append(".\n");
        if (skipPast != null) {
            builder.append("  Skipped:");
            for (int i = 0; i < start; i++) {
                builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
            }
            builder.append('\n');
        }
        builder.append("  Tried:");
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
        }
        throw new IllegalArgumentException(builder.toString());
    }

    /**
     * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
     * {@linkplain #converterFactories() factories}.
     * <p>
     * 创建转换器
     *
     * @param type 回调 需要的返回类型
     * @throws IllegalArgumentException if no converter available for {@code type}.
     */
    public <T> Converter<ResponseBody, T> responseBodyConverter(Type type, Annotation[] annotations) {
        return nextResponseBodyConverter(null, type, annotations);
    }

    /**
     * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
     * {@linkplain #converterFactories() factories} except {@code skipPast}.
     * <p>
     * 创建转换器
     *
     * @param skipPast 跳过的位置
     * @throws IllegalArgumentException if no converter available for {@code type}.
     */
    public <T> Converter<ResponseBody, T> nextResponseBodyConverter(Converter.Factory skipPast,
                                                                    Type type, Annotation[] annotations) {
        checkNotNull(type, "type == null");
        checkNotNull(annotations, "annotations == null");

        // 这里的逻辑和  CallAdapter哪里一样 遍历获取 找到一个就返回
        int start = converterFactories.indexOf(skipPast) + 1;
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            // 工厂  创建 转换器
            Converter<ResponseBody, ?> converter =
                    converterFactories.get(i).responseBodyConverter(type, annotations, this);
            if (converter != null) {
                //noinspection unchecked
                return (Converter<ResponseBody, T>) converter;
            }
        }

        StringBuilder builder = new StringBuilder("Could not locate ResponseBody converter for ")
                .append(type)
                .append(".\n");
        if (skipPast != null) {
            builder.append("  Skipped:");
            for (int i = 0; i < start; i++) {
                builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
            }
            builder.append('\n');
        }
        builder.append("  Tried:");
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
        }
        throw new IllegalArgumentException(builder.toString());
    }

    /**
     * Returns a {@link Converter} for {@code type} to {@link String} from the available
     * {@linkplain #converterFactories() factories}.
     * <p>
     * 获取 String转换器 话说 这个转换器 用的不得多吧
     */
    public <T> Converter<T, String> stringConverter(Type type, Annotation[] annotations) {
        checkNotNull(type, "type == null");
        checkNotNull(annotations, "annotations == null");

        // 遍历所有的转换器工厂 找一个就返回
        for (int i = 0, count = converterFactories.size(); i < count; i++) {
            Converter<?, String> converter =
                    converterFactories.get(i).stringConverter(type, annotations, this);
            if (converter != null) {
                //noinspection unchecked
                return (Converter<T, String>) converter;
            }
        }

        // Nothing matched. Resort to default converter which just calls toString().
        //noinspection unchecked
        // 没有的 返回一个默认的 就是简单的 toString 处理
        return (Converter<T, String>) BuiltInConverters.ToStringConverter.INSTANCE;
    }

    /**
     * The executor used for {@link Callback} methods on a {@link Call}. This may be {@code null},
     * in which case callbacks should be made synchronously on the background thread.
     */
    public Executor callbackExecutor() {
        return callbackExecutor;
    }

    /**
     * Build a new {@link Retrofit}.
     * <p>
     * Calling {@link #baseUrl} is required before calling {@link #build()}. All other methods
     * are optional.
     * <p>
     * Builder 模式 在这里 设置 一些值
     */
    public static final class Builder {
        private Platform platform;
        private okhttp3.Call.Factory callFactory;
        private HttpUrl baseUrl;
        private List<Converter.Factory> converterFactories = new ArrayList<>();
        private List<CallAdapter.Factory> adapterFactories = new ArrayList<>();
        private Executor callbackExecutor;
        private boolean validateEagerly;

        /**
         * @param platform 平台
         */
        Builder(Platform platform) {
            this.platform = platform;
            // Add the built-in converter factory first. This prevents overriding its behavior but also
            // ensures correct behavior when using converters that consume all types.
            converterFactories.add(new BuiltInConverters());
        }

        public Builder() {
            // 获取平台 和 1.x 一样 多了一个 java8 的平台
            this(Platform.get());
        }

        /**
         * The HTTP client used for requests.
         * <p>
         * This is a convenience method for calling {@link #callFactory}.
         * <p>
         * Note: This method <b>does not</b> make a defensive copy of {@code client}. Changes to its
         * settings will affect subsequent requests. Pass in a {@linkplain OkHttpClient#clone() cloned}
         * instance to prevent this if desired.
         * <p>
         * 设置 Http 请求 客户端  只能是 okHttp的
         */
        public Builder client(OkHttpClient client) {
            return callFactory(checkNotNull(client, "client == null"));
        }

        /**
         * Specify a custom call factory for creating {@link Call} instances.
         * <p>
         * Note: Calling {@link #client} automatically sets this value.
         * <p>
         * 设置 Http 请求 客户端
         */
        public Builder callFactory(okhttp3.Call.Factory factory) {
            this.callFactory = checkNotNull(factory, "factory == null");
            return this;
        }

        /**
         * Set the API base URL.
         * <p>
         * 设置 BaseUrl
         *
         * @see #baseUrl(HttpUrl)
         */
        public Builder baseUrl(String baseUrl) {
            checkNotNull(baseUrl, "baseUrl == null");
            // 做了 解析判断处理 是不是 Url
            HttpUrl httpUrl = HttpUrl.parse(baseUrl);
            if (httpUrl == null) {
                throw new IllegalArgumentException("Illegal URL: " + baseUrl);
            }
            return baseUrl(httpUrl);
        }

        /**
         * 这里的参数 HttpUrl 类型 是 okHttp 中的一个类 可以通过 HttpUrl.parse(baseUrl); 获得
         * 一般 还是 按照 上面的方式  public Builder baseUrl(String baseUrl) 设置
         * <p>
         * 和之前版本相比 有几点需要注意的是:
         * <p>
         * BaseUrl 必须 以/ 结尾  方法中做了判断
         * <p>
         * 需要看一下几个例子:
         * <p>
         * 正确的  以/ 结尾
         * Base URL: http://example.com/api/<br>
         * Endpoint: foo/bar/<br> 注意这里没有以 / 开头
         * Result: http://example.com/api/foo/bar/
         * <p>
         * 错误的 没有 以/ 结尾
         * 那么  在这个方法 会对 BaseUrl 做一些处理  最后得到 BaseUrl 是  http://example.com/
         * Base URL: http://example.com/api<br>
         * Endpoint: foo/bar/<br> 注意这里没有以 / 开头
         * Result: http://example.com/foo/bar/
         * <p>
         * <p>
         * 除了 BaseUrl 需要注意  为了 使用者 使用混乱  在方法里面强制加入了 是否是以/ 结尾的判断, 不是的话 会抛出异常
         * 我们在接口上写的 Endpoint 需要注意
         * <p>
         * 如果 接口上的 Endpoint 以/ 开头那么他会 忽略 baseUrl 上的 除了HostName 以外的其他东西
         * 是直接  在hostName 后面加入 Endpoint
         * <p>
         * 例子如下
         * Base URL: http://example.com/api/<br>
         * Endpoint: /foo/bar/<br> 以/ 开头
         * Result: http://example.com/foo/bar/ 看这里 以  /api 不见了
         * <p>
         * Base URL: http://example.com/<br>
         * Endpoint: /foo/bar/<br> 看这里 以  /api 不见了
         * Result: http://example.com/foo/bar/
         * <p>
         * 还有就是 如果 Endpoint 中代用 hostName
         * 那么 将会 完全 忽略 我们设置的BaseUrl
         * Base URL: http://example.com/<br>
         * Endpoint: https://github.com/square/retrofit/<br>
         * Result: https://github.com/square/retrofit/
         * <p>
         * Base URL: http://example.com<br>
         * Endpoint: //github.com/square/retrofit/<br>
         * Result: http://github.com/square/retrofit/ (note the scheme stays 'http')
         * <p>
         * <p>
         * 这样想想看 确实很像 在 html 中的 < a href=""/> 标签 中的地址设置
         * 这样 地址的配置更加 灵活多变
         * <p>
         * <p>
         * Set the API base URL.
         * <p>
         * The specified endpoint values (such as with {@link GET @GET}) are resolved against this
         * value using {@link HttpUrl#resolve(String)}. The behavior of this matches that of an
         * {@code <a href="">} link on a website resolving on the current URL.
         * <p>
         * <b>Base URLs should always end in {@code /}.</b>
         * <p>
         * A trailing {@code /} ensures that endpoints values which are relative paths will correctly
         * append themselves to a base which has path components.
         * <p>
         * <b>Correct:</b><br>
         * Base URL: http://example.com/api/<br>
         * Endpoint: foo/bar/<br>
         * Result: http://example.com/api/foo/bar/
         * <p>
         * <b>Incorrect:</b><br>
         * Base URL: http://example.com/api<br>
         * Endpoint: foo/bar/<br>
         * Result: http://example.com/foo/bar/
         * <p>
         * This method enforces that {@code baseUrl} has a trailing {@code /}.
         * <p>
         * <b>Endpoint values which contain a leading {@code /} are absolute.</b>
         * <p>
         * Absolute values retain only the host from {@code baseUrl} and ignore any specified path
         * components.
         * <p>
         * Base URL: http://example.com/api/<br>
         * Endpoint: /foo/bar/<br>
         * Result: http://example.com/foo/bar/
         * <p>
         * Base URL: http://example.com/<br>
         * Endpoint: /foo/bar/<br>
         * Result: http://example.com/foo/bar/
         * <p>
         * <b>Endpoint values may be a full URL.</b>
         * <p>
         * Values which have a host replace the host of {@code baseUrl} and values also with a scheme
         * replace the scheme of {@code baseUrl}.
         * <p>
         * Base URL: http://example.com/<br>
         * Endpoint: https://github.com/square/retrofit/<br>
         * Result: https://github.com/square/retrofit/
         * <p>
         * Base URL: http://example.com<br>
         * Endpoint: //github.com/square/retrofit/<br>
         * Result: http://github.com/square/retrofit/ (note the scheme stays 'http')
         */
        public Builder baseUrl(HttpUrl baseUrl) {
            checkNotNull(baseUrl, "baseUrl == null");
            List<String> pathSegments = baseUrl.pathSegments();
            if (!"".equals(pathSegments.get(pathSegments.size() - 1))) {
                // 这里做了判断处理  必须以 / 结尾
                throw new IllegalArgumentException("baseUrl must end in /: " + baseUrl);
            }
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Add converter factory for serialization and deserialization of objects.
         * <p>
         * 加入 转换器工厂
         */
        public Builder addConverterFactory(Converter.Factory factory) {
            converterFactories.add(checkNotNull(factory, "factory == null"));
            return this;
        }

        /**
         * Add a call adapter factory for supporting service method return types other than {@link
         * Call}.
         * <p>
         * 加入回调工厂
         * <p>
         * 用于处理 回调的
         */
        public Builder addCallAdapterFactory(CallAdapter.Factory factory) {
            adapterFactories.add(checkNotNull(factory, "factory == null"));
            return this;
        }

        /**
         * The executor on which {@link Callback} methods are invoked when returning {@link Call} from
         * your service method.
         * <p>
         * Note: {@code executor} is not used for {@linkplain #addCallAdapterFactory custom method
         * return types}.
         * <p>
         * 回调时 的线程池  在Android上一般是主线程  这个默认是 主线程
         */
        public Builder callbackExecutor(Executor executor) {
            this.callbackExecutor = checkNotNull(executor, "executor == null");
            return this;
        }

        /**
         * When calling {@link #create} on the resulting {@link Retrofit} instance, eagerly validate
         * the configuration of all methods in the supplied interface.
         * <p>
         * 设置是否 Create 创建动态代理 的时候 加载 并缓存 接口方法 校验接口内部方法参数
         */
        public Builder validateEagerly(boolean validateEagerly) {
            this.validateEagerly = validateEagerly;
            return this;
        }

        /**
         * 构架 加载默认参数
         * <p>
         * Create the {@link Retrofit} instance using the configured values.
         * <p>
         * Note: If neither {@link #client} nor {@link #callFactory} is called a default {@link
         * OkHttpClient} will be created and used.
         */
        public Retrofit build() {
            if (baseUrl == null) {
                throw new IllegalStateException("Base URL required.");
            }

            okhttp3.Call.Factory callFactory = this.callFactory;
            if (callFactory == null) {
                callFactory = new OkHttpClient();
            }

            Executor callbackExecutor = this.callbackExecutor;
            if (callbackExecutor == null) {
                // 回调线程池 默认UI 线程 和1.9 一样
                callbackExecutor = platform.defaultCallbackExecutor();
            }

            // Make a defensive copy of the adapters and add the default Call adapter.
            // 这里会加入 我们设置的 对调处理类型 同时 还会 加入默认的 回调 处理
            List<CallAdapter.Factory> adapterFactories = new ArrayList<>(this.adapterFactories);
            adapterFactories.add(platform.defaultCallAdapterFactory(callbackExecutor));

            // Make a defensive copy of the converters.
            // 设置 转换器
            List<Converter.Factory> converterFactories = new ArrayList<>(this.converterFactories);

            return new Retrofit(callFactory, baseUrl, converterFactories, adapterFactories,
                    callbackExecutor, validateEagerly);
        }
    }
}
