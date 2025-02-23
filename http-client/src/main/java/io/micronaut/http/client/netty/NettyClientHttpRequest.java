/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.client.netty;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.netty.NettyHttpHeaders;
import io.micronaut.http.netty.NettyHttpParameters;
import io.micronaut.http.netty.NettyHttpRequestBuilder;
import io.micronaut.http.netty.cookies.NettyCookie;
import io.micronaut.http.netty.stream.DefaultStreamedHttpRequest;
import io.micronaut.http.netty.stream.StreamedHttpRequest;
import io.micronaut.http.uri.UriBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import org.reactivestreams.Publisher;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default implementation of {@link MutableHttpRequest} for the {@link io.micronaut.http.client.HttpClient}.
 *
 * @param <B> The request body
 * @author Graeme Rocher
 * @since 1.0
 */
@Internal
public class NettyClientHttpRequest<B> implements MutableHttpRequest<B>, NettyHttpRequestBuilder {

    static final CharSequence CHANNEL = "netty_channel";
    private final NettyHttpHeaders headers = new NettyHttpHeaders();
    private final MutableConvertibleValues<Object> attributes = new MutableConvertibleValuesMap<>();
    private final HttpMethod httpMethod;
    private final String httpMethodName;
    private final Map<String, String> cookies = new LinkedHashMap<>(1);
    private URI uri;
    private Object body;
    private NettyHttpParameters httpParameters;
    private ConversionService conversionService = ConversionService.SHARED;

    /**
     * This constructor is actually required for the case of non-standard http methods.
     *
     * @param httpMethod     The http method. CUSTOM value is used for non-standard
     * @param uri            The uri
     * @param httpMethodName Method name. Is the same as httpMethod.name() value for standard http methods.
     */
    NettyClientHttpRequest(HttpMethod httpMethod, URI uri, String httpMethodName) {
        this.httpMethod = httpMethod;
        this.uri = uri;
        this.httpMethodName = httpMethodName;
    }

    /**
     * @param httpMethod The Http method
     * @param uri        The URI
     */
    NettyClientHttpRequest(HttpMethod httpMethod, String uri) {
        this(httpMethod, uri, httpMethod.name());
    }

    /**
     * This constructor is actually required for the case of non-standard http methods.
     *
     * @param httpMethod     The http method. CUSTOM value is used for non-standard
     * @param uri            The uri
     * @param httpMethodName Method name. Is the same as httpMethod.name() value for standard http methods.
     */
    NettyClientHttpRequest(HttpMethod httpMethod, String uri, String httpMethodName) {
        this(httpMethod, URI.create(uri), httpMethodName);
    }

    @Override
    public MutableHttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public MutableConvertibleValues<Object> getAttributes() {
        return attributes;
    }

    @Override
    public MutableHttpRequest<B> cookie(Cookie cookie) {
        if (cookie instanceof NettyCookie nettyCookie) {
            String value = ClientCookieEncoder.LAX.encode(nettyCookie.getNettyCookie());
            cookies.put(cookie.getName(), value);
            String headerValue;
            if (cookies.size() > 1) {
                headerValue = String.join(";", cookies.values());
            } else {
                headerValue = value;
            }
            headers.set(HttpHeaderNames.COOKIE, headerValue);
        } else {
            throw new IllegalArgumentException("Argument is not a Netty compatible Cookie");
        }
        return this;
    }

    @Override
    public MutableHttpRequest<B> cookies(Set<Cookie> cookies) {
        if (cookies.size() > 1) {
            for (Cookie cookie: cookies) {
                if (cookie instanceof NettyCookie nettyCookie) {
                    String value = ClientCookieEncoder.LAX.encode(nettyCookie.getNettyCookie());
                    this.cookies.put(cookie.getName(), value);
                } else {
                    throw new IllegalArgumentException("Argument is not a Netty compatible Cookie");
                }
            }
            headers.set(HttpHeaderNames.COOKIE, String.join(";", this.cookies.values()));
        } else if (!cookies.isEmpty()) {
            cookie(cookies.iterator().next());
        }
        return this;
    }

    @Override
    public MutableHttpRequest<B> uri(URI uri) {
        this.uri = uri;
        return this;
    }

    @Override
    public Optional<B> getBody() {
        return (Optional<B>) Optional.ofNullable(body);
    }

    @Override
    public <T> Optional<T> getBody(Class<T> type) {
        return getBody(Argument.of(type));
    }

    @Override
    public <T> Optional<T> getBody(ArgumentConversionContext<T> conversionContext) {
        return getBody().flatMap(b -> conversionService.convert(b, conversionContext));
    }

    @Override
    public <T> MutableHttpRequest<T> body(T body) {
        this.body = body;
        return (MutableHttpRequest<T>) this;
    }

    @Override
    public Cookies getCookies() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public MutableHttpParameters getParameters() {
        NettyHttpParameters httpParameters = this.httpParameters;
        if (httpParameters == null) {
            synchronized (this) { // double check
                httpParameters = this.httpParameters;
                if (httpParameters == null) {
                    httpParameters = decodeParameters(getUri());
                    this.httpParameters = httpParameters;
                }
            }
        }
        return httpParameters;
    }

    @Override
    public io.micronaut.http.HttpMethod getMethod() {
        return httpMethod;
    }

    @Override
    public URI getUri() {
        return uri;
    }

    private NettyHttpParameters decodeParameters(URI uri) {
        QueryStringDecoder queryStringDecoder = createDecoder(uri);
        return new NettyHttpParameters(queryStringDecoder.parameters(),
                conversionService,
                (name, value) -> {
                    UriBuilder newUri = UriBuilder.of(getUri());
                    newUri.replaceQueryParam(name.toString(), value.toArray());
                    this.uri(newUri.build());
                });
    }

    /**
     * @param uri The URI
     * @return The query string decoder
     */
    protected QueryStringDecoder createDecoder(URI uri) {
        Charset charset = getCharacterEncoding();
        return charset != null ? new QueryStringDecoder(uri, charset) : new QueryStringDecoder(uri);
    }

    private static io.netty.handler.codec.http.HttpMethod getMethod(String httpMethodName) {
        return io.netty.handler.codec.http.HttpMethod.valueOf(httpMethodName);
    }

    private String resolveUriPath() {
        URI uri = getUri();
        if (StringUtils.isNotEmpty(uri.getScheme())) {
            try {
                // obtain just the path
                uri = new URI(null, null, null, -1, uri.getPath(), uri.getQuery(), uri.getFragment());
            } catch (URISyntaxException e) {
                // ignore
            }
        }
        return uri.toString();
    }

    @Override
    public String toString() {
        return getMethodName() + " " + uri;
    }

    @Override
    public String getMethodName() {
        return httpMethodName;
    }

    @NonNull
    @Override
    @Deprecated
    public FullHttpRequest toFullHttpRequest() {
        String uriStr = resolveUriPath();
        io.netty.handler.codec.http.HttpMethod method = getMethod(httpMethodName);
        DefaultFullHttpRequest req;
        if (body != null) {
            if (body instanceof ByteBuf buf) {
                req = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        method,
                        uriStr,
                        buf,
                        headers.getNettyHeaders(),
                        EmptyHttpHeaders.INSTANCE
                );
            } else {
                req = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        method,
                        uriStr,
                        false
                );
                req.headers().setAll(headers.getNettyHeaders());
            }
        } else {
            req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    method,
                    uriStr,
                    Unpooled.EMPTY_BUFFER,
                    headers.getNettyHeaders(),
                    EmptyHttpHeaders.INSTANCE
            );
        }
        return req;
    }

    @NonNull
    @Override
    @Deprecated
    public StreamedHttpRequest toStreamHttpRequest() {
        if (body instanceof Publisher) {
            String uriStr = resolveUriPath();
            io.netty.handler.codec.http.HttpMethod method = getMethod(httpMethodName);
            DefaultStreamedHttpRequest req = new DefaultStreamedHttpRequest(
                                HttpVersion.HTTP_1_1, method, uriStr, false, (Publisher<HttpContent>) body);
            req.headers().setAll(headers.getNettyHeaders());
            return req;
        } else {
            throw new IllegalStateException("Body must be set to a publisher of HTTP content first!");
        }
    }

    @NonNull
    @Override
    @Deprecated
    public HttpRequest toHttpRequest() {
        if (isStream()) {
            return toStreamHttpRequest();
        } else {
            return toFullHttpRequest();
        }
    }

    @Override
    public HttpRequest toHttpRequestWithoutBody() {
        return new DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            getMethod(httpMethodName),
            resolveUriPath(),
            headers.getNettyHeaders()
        );
    }

    @Override
    @Deprecated
    public boolean isStream() {
        return body instanceof Publisher;
    }

    @Override
    public void setConversionService(ConversionService conversionService) {
        this.conversionService = conversionService;
    }
}
