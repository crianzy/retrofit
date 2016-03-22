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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import retrofit.client.Response;
import retrofit.http.Body;
import retrofit.http.EncodedPath;
import retrofit.http.EncodedQuery;
import retrofit.http.EncodedQueryMap;
import retrofit.http.Field;
import retrofit.http.FieldMap;
import retrofit.http.FormUrlEncoded;
import retrofit.http.Header;
import retrofit.http.Headers;
import retrofit.http.Multipart;
import retrofit.http.Part;
import retrofit.http.PartMap;
import retrofit.http.Path;
import retrofit.http.Query;
import retrofit.http.QueryMap;
import retrofit.http.Streaming;
import retrofit.http.RestMethod;
import rx.Observable;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Request metadata about a service interface declaration.
 * <p>
 * 这个是我们定义的 接口方法 最后解析出来的 请求包装类
 */
final class RestMethodInfo {

    private enum ResponseType {
        VOID,// CallBack 的
        OBSERVABLE, //RxJAVA
        OBJECT
    }

    // Upper and lower characters, digits, underscores, and hyphens, starting with a character.
    private static final String PARAM = "[a-zA-Z][a-zA-Z0-9_-]*";
    private static final Pattern PARAM_NAME_REGEX = Pattern.compile(PARAM);
    private static final Pattern PARAM_URL_REGEX = Pattern.compile("\\{(" + PARAM + ")\\}");

    enum RequestType {
        /**
         * No content-specific logic required.
         */
        SIMPLE,
        /**
         * Multi-part request body.
         */
        MULTIPART,
        /**
         * Form URL-encoded request body.
         */
        FORM_URL_ENCODED
    }

    final Method method;

    boolean loaded = false;

    // Method-level details
    final ResponseType responseType;
    final boolean isSynchronous;
    final boolean isObservable;
    Type responseObjectType;
    RequestType requestType = RequestType.SIMPLE;
    String requestMethod;
    boolean requestHasBody;
    String requestUrl;
    Set<String> requestUrlParamNames;
    String requestQuery;
    // 所有的header 集合
    List<retrofit.client.Header> headers;
    // contentType 类型的Header  这是比较重要的一个 header
    String contentTypeHeader;
    boolean isStreaming;

    // Parameter-level details
    Annotation[] requestParamAnnotations;

    /**
     * @param method 我们定义的接口方法
     */
    RestMethodInfo(Method method) {
        this.method = method;
        // 获取返回类型
        responseType = parseResponseType();
        // 是否是同步  如果是 ResponseType.OBJECT 类型的话 ,那么表示 尽在调用方法线程执行请求  数据直接返回, 不再另用 线程池
        isSynchronous = (responseType == ResponseType.OBJECT);
        // 是否是 RxJava 的
        isObservable = (responseType == ResponseType.OBSERVABLE);
    }

    private RuntimeException methodError(String message, Object... args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        return new IllegalArgumentException(
                method.getDeclaringClass().getSimpleName() + "." + method.getName() + ": " + message);
    }

    private RuntimeException parameterError(int index, String message, Object... args) {
        return methodError(message + " (parameter #" + (index + 1) + ")", args);
    }

    synchronized void init() {
        if (loaded) return;

        parseMethodAnnotations();
        parseParameters();

        loaded = true;
    }

    /**
     * Loads {@link #requestMethod} and {@link #requestType}.
     *
     * 加载方法注解
     */
    private void parseMethodAnnotations() {
        //这里获取到的注解 知识 方法上的注解  参数上的注解不在这里
        for (Annotation methodAnnotation : method.getAnnotations()) {
            Class<? extends Annotation> annotationType = methodAnnotation.annotationType();
            RestMethod methodInfo = null;

            // Look for a @RestMethod annotation on the parameter annotation indicating request method.
            for (Annotation innerAnnotation : annotationType.getAnnotations()) {
                // 循环 注解 里面的注解
                // 如下 , 循环 GET 注解  可以获取到 Documented  Retention RestMethod Target 这些注解
//                @Documented
//                @Target(METHOD)
//                @Retention(RUNTIME)
//                @RestMethod("GET")
//                public @interface GET {
//                    String value();
//                }

                // 获取请求方式的 注解 信息 GET POST 这些注解里面 都包含有 RestMethod 注解
                if (RestMethod.class == innerAnnotation.annotationType()) {
                    methodInfo = (RestMethod) innerAnnotation;
                    break;
                }
            }

            if (methodInfo != null) {
                // methodInfo 表示当期请求 是 GET 或 POST 请求
                if (requestMethod != null) {
                    throw methodError("Only one HTTP method is allowed. Found: %s and %s.", requestMethod,
                            methodInfo.value());
                }
                String path;
                try {
                    // 获取当前注解的值  请求路径
                    path = (String) annotationType.getMethod("value").invoke(methodAnnotation);
                } catch (Exception e) {
                    // 注解类型错误,  没有value 方法
                    throw methodError("Failed to extract String 'value' from @%s annotation.",
                            annotationType.getSimpleName());
                }
                // 解析请求路径
                parsePath(path);
                // 请求类型  get post
                requestMethod = methodInfo.value();
                // 是否 有body
                requestHasBody = methodInfo.hasBody();
            } else if (annotationType == Headers.class) {
                // 获取 header 信息
                String[] headersToParse = ((Headers) methodAnnotation).value();
                if (headersToParse.length == 0) {
                    throw methodError("@Headers annotation is empty.");
                }
                headers = parseHeaders(headersToParse);
            } else if (annotationType == Multipart.class) {
                // 一开始的时候 肯定是 SIMPLE类型 写死了的,
                // 如果这里发现不是SIMPLE 那么说明 有其他注解 修改了这个值 抛出异常
                if (requestType != RequestType.SIMPLE) {
                    throw methodError("Only one encoding annotation is allowed.");
                }
                requestType = RequestType.MULTIPART;
            } else if (annotationType == FormUrlEncoded.class) {
                if (requestType != RequestType.SIMPLE) {
                    throw methodError("Only one encoding annotation is allowed.");
                }
                // 使用URL 的编码格式
                requestType = RequestType.FORM_URL_ENCODED;
            } else if (annotationType == Streaming.class) {
                // TODO 表示返回 数据流
                if (responseObjectType != Response.class) {
                    throw methodError(
                            "Only methods having %s as data type are allowed to have @%s annotation.",
                            Response.class.getSimpleName(), Streaming.class.getSimpleName());
                }
                isStreaming = true;
            }
        }

        // 请求方式不能为空
        if (requestMethod == null) {
            throw methodError("HTTP method annotation is required (e.g., @GET, @POST, etc.).");
        }
        if (!requestHasBody) {
            // 没有body 就不能是 MULTIPART 或 FORM_URL_ENCODED
            if (requestType == RequestType.MULTIPART) {
                throw methodError(
                        "Multipart can only be specified on HTTP methods with request body (e.g., @POST).");
            }
            if (requestType == RequestType.FORM_URL_ENCODED) {
                throw methodError("FormUrlEncoded can only be specified on HTTP methods with request body "
                        + "(e.g., @POST).");
            }
        }
    }

    /**
     * Loads {@link #requestUrl}, {@link #requestUrlParamNames}, and {@link #requestQuery}.
     *
     * 解析 请求路基
     */
    private void parsePath(String path) {
        if (path == null || path.length() == 0 || path.charAt(0) != '/') {
            // 必须以/ 开头
            throw methodError("URL path \"%s\" must start with '/'.", path);
        }

        // Get the relative URL path and existing query string, if present.
        String url = path;
        String query = null;
        int question = path.indexOf('?');
        if (question != -1 && question < path.length() - 1) {
            url = path.substring(0, question);
            query = path.substring(question + 1);

            // Ensure the query string does not have any named parameters.
            // 确保 URL ? 后面的 查询参数中 是有 {} 有的话 说明 参数不确定 ,
            // 那么不应再path中写出来, 而是应该在方法参数中是 Query 注解
            Matcher queryParamMatcher = PARAM_URL_REGEX.matcher(query);
            if (queryParamMatcher.find()) {
                // 查询参数 不确定的话 应该使用 Query 注解
                throw methodError("URL query string \"%s\" must not have replace block. For dynamic query"
                        + " parameters use @Query.", query);
            }
        }

        Set<String> urlParams = parsePathParameters(path);

        requestUrl = url;
        // url 中的 参数集合 // /api/v1/article/{id}/delete/   每个{} 都是一个 数据
        requestUrlParamNames = urlParams;
        requestQuery = query;
    }

    List<retrofit.client.Header> parseHeaders(String[] headers) {
        List<retrofit.client.Header> headerList = new ArrayList<retrofit.client.Header>();
        for (String header : headers) {
            int colon = header.indexOf(':');
            if (colon == -1 || colon == 0 || colon == header.length() - 1) {
                throw methodError("@Headers value must be in the form \"Name: Value\". Found: \"%s\"",
                        header);
            }
            String headerName = header.substring(0, colon);
            String headerValue = header.substring(colon + 1).trim();
            if ("Content-Type".equalsIgnoreCase(headerName)) {
                contentTypeHeader = headerValue;
            } else {
                headerList.add(new retrofit.client.Header(headerName, headerValue));
            }
        }
        return headerList;
    }

    /**
     * Loads {@link #responseObjectType}. Returns {@code true} if method is synchronous.
     *
     * 获取 ResponseType 的类型
     */
    private ResponseType parseResponseType() {
        // Synchronous methods have a non-void return type.
        // Observable methods have a return type of Observable.
        // 获取方法的 返回值类型
        Type returnType = method.getGenericReturnType();

        // Asynchronous methods should have a Callback type as the last argument.
        // 方法的最后一个参数
        Type lastArgType = null;
        Class<?> lastArgClass = null;
        // 获取方法的参数 集合
        Type[] parameterTypes = method.getGenericParameterTypes();
        if (parameterTypes.length > 0) {
            // 检查 方法的最后一个参数
            Type typeToCheck = parameterTypes[parameterTypes.length - 1];
            lastArgType = typeToCheck;
            //TODO 这里还不是 很明白
            if (typeToCheck instanceof ParameterizedType) {
                // 表示是 参数类型 不是那些 int 这些 基本参数类型
                // getRawType 获取原本的类型
                typeToCheck = ((ParameterizedType) typeToCheck).getRawType();
            }
            if (typeToCheck instanceof Class) {
                // 如果不是基本参数类型的话
                lastArgClass = (Class<?>) typeToCheck;
            }
        }

        // 获取是否有 返回值
        boolean hasReturnType = returnType != void.class;
        // 判断是否有 callBack
        boolean hasCallback = lastArgClass != null && Callback.class.isAssignableFrom(lastArgClass);

        // Check for invalid configurations.
        if (hasReturnType && hasCallback) {
            // 返回值 和 callBack 两个都有
            throw methodError("Must have return type or Callback as last argument, not both.");
        }
        if (!hasReturnType && !hasCallback) {
            // 返回值 和 callBack 两个都 没有
            throw methodError("Must have either a return type or Callback as last argument.");
        }

        if (hasReturnType) {
            // 如果有返回值
            if (Platform.HAS_RX_JAVA) {
                Class rawReturnType = Types.getRawType(returnType);
                // 获取 类型 并判断是不是 Observable类型
                if (RxSupport.isObservable(rawReturnType)) {
                    //TODO
                    returnType = RxSupport.getObservableType(returnType, rawReturnType);
                    responseObjectType = getParameterUpperBound((ParameterizedType) returnType);
                    return ResponseType.OBSERVABLE;
                }
            }
            responseObjectType = returnType;
            return ResponseType.OBJECT;
        }

        lastArgType = Types.getSupertype(lastArgType, Types.getRawType(lastArgType), Callback.class);
        if (lastArgType instanceof ParameterizedType) {
            // CallBack 的处理
            responseObjectType = getParameterUpperBound((ParameterizedType) lastArgType);
            return ResponseType.VOID;
        }

        throw methodError("Last parameter must be of type Callback<X> or Callback<? super X>.");
    }

    private static Type getParameterUpperBound(ParameterizedType type) {
        Type[] types = type.getActualTypeArguments();
        for (int i = 0; i < types.length; i++) {
            Type paramType = types[i];
            if (paramType instanceof WildcardType) {
                types[i] = ((WildcardType) paramType).getUpperBounds()[0];
            }
        }
        return types[0];
    }

    /**
     * Loads {@link #requestParamAnnotations}. Must be called after {@link #parseMethodAnnotations()}.
     *
     * 解析参数信息
     */
    private void parseParameters() {
        // 参数数组
        Class<?>[] methodParameterTypes = method.getParameterTypes();

        // 获取一个  参数 对应 注解的 二维数组
        Annotation[][] methodParameterAnnotationArrays = method.getParameterAnnotations();

        // 这个长度 是参数长度
        int count = methodParameterAnnotationArrays.length;
        if (!isSynchronous && !isObservable) {
            // 如果是 callBack  最后一个参数 是Callback 不需要处理
            count -= 1; // Callback is last argument when not a synchronous method.
        }

        Annotation[] requestParamAnnotations = new Annotation[count];

        boolean gotField = false;
        boolean gotPart = false;
        boolean gotBody = false;

        // 循环二维数组
        for (int i = 0; i < count; i++) {
            Class<?> methodParameterType = methodParameterTypes[i];
            Annotation[] methodParameterAnnotations = methodParameterAnnotationArrays[i];
            if (methodParameterAnnotations != null) {
                for (Annotation methodParameterAnnotation : methodParameterAnnotations) {
                    // 循环 每个参数中的 注解
                    Class<? extends Annotation> methodAnnotationType =
                            methodParameterAnnotation.annotationType();

                    if (methodAnnotationType == Path.class) {
                        // path 注解
                        String name = ((Path) methodParameterAnnotation).value();
                        validatePathName(i, name);
                    } else if (methodAnnotationType == EncodedPath.class) {
                        // 类似 path 注解
                        String name = ((EncodedPath) methodParameterAnnotation).value();
                        validatePathName(i, name);
                    } else if (methodAnnotationType == Query.class) {
                        // Nothing to do.
                    } else if (methodAnnotationType == EncodedQuery.class) {
                        // Nothing to do.
                    } else if (methodAnnotationType == QueryMap.class) {
                        //TODO map ???
                        if (!Map.class.isAssignableFrom(methodParameterType)) {
                            throw parameterError(i, "@QueryMap parameter type must be Map.");
                        }

                    } else if (methodAnnotationType == EncodedQueryMap.class) {
                        if (!Map.class.isAssignableFrom(methodParameterType)) {
                            throw parameterError(i, "@EncodedQueryMap parameter type must be Map.");
                        }

                    } else if (methodAnnotationType == Header.class) {
                        // Nothing to do.
                    } else if (methodAnnotationType == Field.class) {
                        // 表示 field  需要配合 FORM_URL_ENCODED 一起使用
                        if (requestType != RequestType.FORM_URL_ENCODED) {
                            throw parameterError(i, "@Field parameters can only be used with form encoding.");
                        }

                        gotField = true;
                    } else if (methodAnnotationType == FieldMap.class) {
                        if (requestType != RequestType.FORM_URL_ENCODED) {
                            throw parameterError(i, "@FieldMap parameters can only be used with form encoding.");
                        }
                        if (!Map.class.isAssignableFrom(methodParameterType)) {
                            throw parameterError(i, "@FieldMap parameter type must be Map.");
                        }

                        gotField = true;
                    } else if (methodAnnotationType == Part.class) {
                        if (requestType != RequestType.MULTIPART) {
                            throw parameterError(i, "@Part parameters can only be used with multipart encoding.");
                        }

                        gotPart = true;
                    } else if (methodAnnotationType == PartMap.class) {
                        if (requestType != RequestType.MULTIPART) {
                            throw parameterError(i,
                                    "@PartMap parameters can only be used with multipart encoding.");
                        }
                        if (!Map.class.isAssignableFrom(methodParameterType)) {
                            throw parameterError(i, "@PartMap parameter type must be Map.");
                        }

                        gotPart = true;
                    } else if (methodAnnotationType == Body.class) {
                        if (requestType != RequestType.SIMPLE) {
                            throw parameterError(i,
                                    "@Body parameters cannot be used with form or multi-part encoding.");
                        }
                        if (gotBody) {
                            // 表示 有多个Body
                            throw methodError("Multiple @Body method annotations found.");
                        }

                        gotBody = true;
                    } else {
                        // 其他的参数不重要
                        // This is a non-Retrofit annotation. Skip to the next one.
                        continue;
                    }

                    // 表示当前 位置的 对应的 请求 注解 必须是 null
                    if (requestParamAnnotations[i] != null) {
                        // 和上面一样的 重复判断的逻辑
                        throw parameterError(i,
                                "Multiple Retrofit annotations found, only one allowed: @%s, @%s.",
                                requestParamAnnotations[i].annotationType().getSimpleName(),
                                methodAnnotationType.getSimpleName());
                    }
                    requestParamAnnotations[i] = methodParameterAnnotation;
                }
            }

            if (requestParamAnnotations[i] == null) {
                // 没有注解
                throw parameterError(i, "No Retrofit annotation found.");
            }
        }

        if (requestType == RequestType.SIMPLE && !requestHasBody && gotBody) {
            // 方式为 SIMPLE 但是 有了 Body
            throw methodError("Non-body HTTP method cannot contain @Body or @TypedOutput.");
        }
        if (requestType == RequestType.FORM_URL_ENCODED && !gotField) {
            // 方式为 FORM_URL_ENCODED 但是没有 File 注解的参数
            throw methodError("Form-encoded method must contain at least one @Field.");
        }
        if (requestType == RequestType.MULTIPART && !gotPart) {
            // 为 MULTIPART 但是没有 MULTIPART 的注解
            throw methodError("Multipart method must contain at least one @Part.");
        }

        this.requestParamAnnotations = requestParamAnnotations;
    }

    /**
     * 校验 参数 名字
     * @param index
     * @param name
     */
    private void validatePathName(int index, String name) {
        if (!PARAM_NAME_REGEX.matcher(name).matches()) {
            // 参数 名字 必须是 数字 和 字母
            throw parameterError(index, "@Path parameter name must match %s. Found: %s",
                    PARAM_URL_REGEX.pattern(), name);
        }
        // Verify URL replacement name is actually present in the URL path.
        if (!requestUrlParamNames.contains(name)) {
            // 如果 path 中带 {str} 参数的集合中 不包含 这个参数名字的话 , 抛出异常
            throw parameterError(index, "URL \"%s\" does not contain \"{%s}\".", requestUrl, name);
        }
    }

    /**
     * Gets the set of unique path parameters used in the given URI. If a parameter is used twice
     * in the URI, it will only show up once in the set.
     *
     * 匹配 Path 中 {} 参数 但顺序的 Set 集合
     */
    static Set<String> parsePathParameters(String path) {
        Matcher m = PARAM_URL_REGEX.matcher(path);
        Set<String> patterns = new LinkedHashSet<String>();
        while (m.find()) {
            patterns.add(m.group(1));
        }
        return patterns;
    }

    /**
     * Indirection to avoid log complaints if RxJava isn't present.
     */
    private static final class RxSupport {
        public static boolean isObservable(Class rawType) {
            return rawType == Observable.class;
        }

        public static Type getObservableType(Type contextType, Class contextRawType) {
            return Types.getSupertype(contextType, contextRawType, Observable.class);
        }
    }
}
