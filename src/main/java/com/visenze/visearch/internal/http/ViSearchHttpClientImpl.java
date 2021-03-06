package com.visenze.visearch.internal.http;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.visenze.visearch.ClientConfig;
import com.visenze.visearch.ResponseMessages;
import com.visenze.visearch.internal.InternalViSearchException;
import org.apache.http.*;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ViSearchHttpClientImpl implements ViSearchHttpClient {

    private final String endpoint;
    private final CloseableHttpClient httpClient;
    private final ClientConfig clientConfig;
    private final UsernamePasswordCredentials credentials;

    public ViSearchHttpClientImpl(String endpoint, String accessKey, String secretKey, CloseableHttpClient httpClient) {
        this.endpoint = endpoint;
        credentials = new UsernamePasswordCredentials(accessKey, secretKey);
        this.httpClient = httpClient;
        this.clientConfig = new ClientConfig();
    }

    public ViSearchHttpClientImpl(String endpoint, String accessKey, String secretKey) {
        this(endpoint, accessKey, secretKey, new ClientConfig());
    }

    public ViSearchHttpClientImpl(String endpoint, String accessKey, String secretKey, ClientConfig clientConfig) {
        this.endpoint = endpoint;
        this.clientConfig = clientConfig;
        RequestConfig conf = RequestConfig
                .custom()
                .setConnectTimeout(clientConfig.getConnectionTimeout())
                .setSocketTimeout(clientConfig.getSocketTimeout())
                .build();
        credentials = new UsernamePasswordCredentials(accessKey, secretKey);
        this.httpClient = HttpClientBuilder
                .create()
                .setMaxConnTotal(clientConfig.getMaxConnection())
                .setMaxConnPerRoute(clientConfig.getMaxConnection())
                .setDefaultRequestConfig(conf)
                .build();
    }

    @Override
    public ViSearchHttpResponse get(String path, Multimap<String, String> params) {
        HttpUriRequest request = buildGetRequest(endpoint + path, params);
        return getResponse(request);
    }

    @Override
    public ViSearchHttpResponse post(String path, Multimap<String, String> params) {
        HttpUriRequest request = buildPostRequest(endpoint + path, params);
        return getResponse(request);
    }

    @Override
    public ViSearchHttpResponse postImage(String path, Multimap<String, String> params, File file) {
        HttpUriRequest request = buildPostRequestForImage(endpoint + path, params, file);
        return getResponse(request);
    }

    @Override
    public ViSearchHttpResponse postImage(String path, Multimap<String, String> params, InputStream inputStream, String filename) {
        HttpUriRequest request = buildPostRequestForImage(endpoint + path, params, inputStream, filename);
        return getResponse(request);
    }

    private HttpUriRequest buildGetRequest(String url, Multimap<String, String> params) {
        return RequestBuilder
                .get()
                .setUri(buildGetUri(url, mapToNameValuePair(params)))
                .build();
    }

    private static URI buildGetUri(String url, List<NameValuePair> nameValuePairList) {
        try {
            return new URIBuilder(url).addParameters(nameValuePairList).build();
        } catch (URISyntaxException e) {
            throw new InternalViSearchException(ResponseMessages.INVALID_ENDPOINT, e);
            //throw new ViSearchException("There was an error parsing the ViSearch endpoint. Please ensure " +
            //        "that your provided ViSearch endpoint is a well-formed URL and try again.", e);
        }
    }

    private static URI buildPostUri(String url) {
        try {
            return new URIBuilder(url).build();
        } catch (URISyntaxException e) {
            throw new InternalViSearchException(ResponseMessages.INVALID_ENDPOINT, e);
            //throw new ViSearchException("There was an error parsing the ViSearch endpoint. Please ensure " +
            //        "that your provided ViSearch endpoint is a well-formed URL and try again.", e);
        }
    }

    private HttpUriRequest buildPostRequest(String url, Multimap<String, String> params) {
        return RequestBuilder
                .post()
                .setUri(buildPostUri(url))
                .setEntity(new UrlEncodedFormEntity(mapToNameValuePair(params), Consts.UTF_8))
                .setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_FORM_URLENCODED.withCharset(Consts.UTF_8).toString())
                .build();
    }

    private static HttpUriRequest buildMultipartPostRequest(String url, HttpEntity entity) {
        HttpPost httpPost = new HttpPost(url);
        httpPost.setEntity(entity);
        return httpPost;
    }

    private static HttpUriRequest buildPostRequestForImage(String url, Multimap<String, String> params, File file) {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setCharset(Charset.forName("utf-8"));
        for (Map.Entry<String, String> entry : params.entries()) {
            builder.addTextBody(entry.getKey(), entry.getValue(), ContentType.TEXT_PLAIN);
        }
        builder.addBinaryBody("image", file);
        HttpEntity entity = builder.build();
        return buildMultipartPostRequest(url, entity);
    }

    private static HttpUriRequest buildPostRequestForImage(String url, Multimap<String, String> params, InputStream inputStream, String filename) {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        for (Map.Entry<String, String> entry : params.entries()) {
            builder.addTextBody(entry.getKey(), entry.getValue(), ContentType.TEXT_PLAIN);
        }
        builder.addPart("image", new InputStreamBody(inputStream, filename));
        HttpEntity entity = builder.build();
        return buildMultipartPostRequest(url, entity);
    }

    private ViSearchHttpResponse getResponse(HttpUriRequest request) {
        addAuthHeader(request);
        addUserAgentHeader(request);
        CloseableHttpResponse response = executeRequest(request);
        try {
            Map<String, String> headers = Maps.newHashMap();
            Header[] responseHeaders = response.getAllHeaders();
            if (responseHeaders != null) {
                for (Header header : responseHeaders) {
                    headers.put(header.getName(), header.getValue());
                }
            }
            ViSearchHttpResponse response1 = new ViSearchHttpResponse(response);
            response1.setHeaders(headers);
            return response1;
        } catch (IllegalArgumentException e) {
            throw new InternalViSearchException(ResponseMessages.SYSTEM_ERROR, e);
            // throw new NetworkException("A network error occurred when reading response from the ViSearch endpoint. " +
            //        "Please check your network connectivity and try again.", e);
        }
    }

    private void addAuthHeader(HttpUriRequest request) {
        try {
            request.addHeader(new BasicScheme().authenticate(credentials, request, null));
        } catch (AuthenticationException e) {
            throw new InternalViSearchException(ResponseMessages.UNAUTHORIZED, e);
            // throw new com.visenze.visearch.internal.AuthenticationException("There was an error generating the " +
            //        "HTTP basic authentication header. Please check your access key and secret key and try again", e);
        }
    }

    private void addUserAgentHeader(HttpUriRequest request) {
        String userAgent = clientConfig.getUserAgent();
        if (!userAgent.equals(ClientConfig.DEFAULT_USER_AGENT)) {
            userAgent += " " + ClientConfig.DEFAULT_USER_AGENT;
        }
        request.addHeader(HttpHeaders.USER_AGENT, userAgent);
    }

    private CloseableHttpResponse executeRequest(HttpUriRequest request) {
        try {
            return httpClient.execute(request);
        } catch (IOException e) {
            throw new InternalViSearchException(ResponseMessages.NETWORK_ERROR, e);
            // throw new NetworkException("A network error occurred when requesting to the ViSearch endpoint. " +
            //        "Please check your network connectivity and try again.", e);
        }
    }

    private List<NameValuePair> mapToNameValuePair(Multimap<String, ?> params) {
        List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        for (Map.Entry<String, ?> entry : params.entries()) {
            pairs.add(new BasicNameValuePair(entry.getKey(), entry.getValue().toString()));
        }
        return pairs;
    }

}
