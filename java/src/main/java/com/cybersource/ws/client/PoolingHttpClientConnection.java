package com.cybersource.ws.client;


import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.NoHttpResponseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.TransformerException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.cybersource.ws.client.Utility.*;

/**
 * Creates pooling http client connection flow. It maintains a pool of
 * http client connections and is able to service connection requests
 * from multiple execution threads.
 *
 * Class helps in posting the Request document for the Transaction using HttpClient.
 */
public class PoolingHttpClientConnection extends Connection {
    private HttpPost httpPost = null;
    private HttpClientContext httpContext = null;
    private CloseableHttpResponse httpResponse = null;

    private static CloseableHttpClient httpClient = null;
    private static IdleConnectionMonitorThread staleMonitorThread;
    private final static String STALE_CONNECTION_MONITOR_THREAD_NAME = "http-stale-connection-cleaner-thread";
    private static PoolingHttpClientConnectionManager connectionManager = null;

    /**
     *
     * @param mc
     * @param builder
     * @param logger
     * @throws ClientException
     */
    PoolingHttpClientConnection(MerchantConfig mc, DocumentBuilder builder, LoggerWrapper logger) throws ClientException {
        super(mc, builder, logger);
        initializeConnectionManager(mc);
        logger.log(Logger.LT_INFO, "Using PoolingHttpClient for connections.");
    }

    /**
     * Initialize pooling connection manager with max connections based on properties
     *
     * @param merchantConfig
     * @throws ClientException
     */
    private void initializeConnectionManager(MerchantConfig merchantConfig) throws ClientException {
        if (connectionManager == null) {
            synchronized (PoolingHttpClientConnection.class) {
                if (connectionManager == null) {
                    String url = merchantConfig.getEffectiveServerURL();
                    try {
                        URI uri = new URI(url);
                        String hostname = uri.getHost();
                        connectionManager = new PoolingHttpClientConnectionManager();
                        connectionManager.setDefaultMaxPerRoute(merchantConfig.getDefaultMaxConnectionsPerRoute());
                        connectionManager.setDefaultSocketConfig(SocketConfig.custom().setSoKeepAlive(true).setSoTimeout(merchantConfig.getSocketTimeoutMs()).build());
                        connectionManager.setMaxTotal(merchantConfig.getMaxConnections());
                        connectionManager.setValidateAfterInactivity(merchantConfig.getValidateAfterInactivityMs());
                        final HttpHost httpHost = new HttpHost(hostname);
                        connectionManager.setMaxPerRoute(new HttpRoute(httpHost), merchantConfig.getMaxConnectionsPerRoute());
                        initHttpClient(merchantConfig, connectionManager);
                        startStaleConnectionMonitorThread(merchantConfig, connectionManager);
                        if (merchantConfig.isShutdownHookEnabled()) {
                            addShutdownHook();
                        }
                    } catch (Exception e) {
                        logger.log(Logger.LT_FAULT, "invalid server url");
                        throw new ClientException(e, logger);
                    }
                }
            }
        }
    }

    /**
     * @param merchantConfig
     * @param poolingHttpClientConnManager
     */
    protected void initHttpClient(MerchantConfig merchantConfig, PoolingHttpClientConnectionManager poolingHttpClientConnManager) {
        RequestConfig.Builder requestConfigBuilder = RequestConfig.custom()
                .setSocketTimeout(merchantConfig.getSocketTimeoutMs())
                .setConnectionRequestTimeout(merchantConfig.getConnectionRequestTimeoutMs())
                //This we added to check every connection before leasing.
                .setStaleConnectionCheckEnabled(merchantConfig.isStaleConnectionCheckEnabled())
                .setConnectTimeout(merchantConfig.getConnectionTimeoutMs());

        HttpClientBuilder httpClientBuilder = HttpClients.custom()
                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                .setConnectionManager(poolingHttpClientConnManager);

        if(merchantConfig.isAllowRetry()){
            httpClientBuilder.setRetryHandler(new CustomRetryHandler());
        }

        setProxy(httpClientBuilder, requestConfigBuilder, merchantConfig);

        httpClient = httpClientBuilder
                .setDefaultRequestConfig(requestConfigBuilder.build())
                .build();
    }

    /**
     * Create and start thread to clean Idle,Stale and Expired connections
     *
     * @param merchantConfig
     * @param poolingHttpClientConnManager
     */
    private void startStaleConnectionMonitorThread(MerchantConfig merchantConfig, PoolingHttpClientConnectionManager poolingHttpClientConnManager) {
        staleMonitorThread = new IdleConnectionMonitorThread(poolingHttpClientConnManager, merchantConfig.getEvictThreadSleepTimeMs(), merchantConfig.getMaxKeepAliveTimeMs());
        staleMonitorThread.setName(STALE_CONNECTION_MONITOR_THREAD_NAME);
        staleMonitorThread.setDaemon(true);
        staleMonitorThread.start();
    }

    /**
     * To post the request using http pool connection
     *
     * @param request
     * @param startTime
     * @throws IOException
     * @throws TransformerException
     */
    @Override
    void postDocument(Document request, long startTime) throws IOException, TransformerException {
        String serverURL = mc.getEffectiveServerURL();
        httpPost = new HttpPost(serverURL);
        String requestString = documentToString(request);
        StringEntity stringEntity = new StringEntity(requestString, "UTF-8");
        httpPost.setEntity(stringEntity);
        httpPost.setHeader(Utility.SDK_ELAPSED_TIMESTAMP, String.valueOf(System.currentTimeMillis() - startTime));
        httpPost.setHeader(Utility.ORIGIN_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        logRequestHeaders();
        httpContext = HttpClientContext.create();
        logger.log(Logger.LT_INFO,
                "Sending " + requestString.length() + " bytes to " + serverURL);
        httpResponse = httpClient.execute(httpPost, httpContext);
    }

    /**
     * To check whether request sent or not
     *
     * @return boolean
     */
    @Override
    public boolean isRequestSent() {
        return httpContext != null && httpContext.isRequestSent();
    }

    /**
     * Enable JVM runtime shutdown hook
     */
    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(this.createShutdownHookThread());
    }

    /**
     * Thread which calls shutdown method
     *
     * @return
     */
    private Thread createShutdownHookThread() {
        return new Thread() {
            public void run() {
                try {
                    PoolingHttpClientConnection.onShutdown();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    /**
     * To close the httpClient, connectionManager, staleMonitorThread
     * when application got shutdown
     *
     * @throws IOException
     */
    public static void onShutdown() throws IOException {
        if (httpClient != null) {
            httpClient.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (staleMonitorThread != null && staleMonitorThread.isAlive()) {
            staleMonitorThread.shutdown();
        }
        //wait before shutdown.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException var4) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * To close httpResponse
     *
     * @throws ClientException
     */
    @Override
    public void release() throws ClientException {
        try {
            if (httpResponse != null) {
                EntityUtils.consume(httpResponse.getEntity());
                httpResponse.close();
            }
        } catch (IOException e) {
            throw new ClientException(e, logger);
        }
    }

    /**
     * @return int
     */
    @Override
    int getHttpResponseCode() {
        return httpResponse != null ? httpResponse.getStatusLine().getStatusCode() : -1;
    }

    /**
     *
     * @return InputStream
     * @throws IOException
     */
    @Override
    InputStream getResponseStream() throws IOException {
        return httpResponse != null ? httpResponse.getEntity().getContent() : null;
    }

    /**
     *
     * @return InputStream
     * @throws IOException
     */
    @Override
    InputStream getResponseErrorStream() throws IOException {
        return getResponseStream();
    }

    @Override
    public void logRequestHeaders() {
        if (mc.getEnableLog() && httpPost != null) {
            List<Header> reqHeaders = Arrays.asList(httpPost.getAllHeaders());
            logger.log(Logger.LT_INFO, "Request Headers: " + reqHeaders);
        }

    }

    @Override
    public void logResponseHeaders() {
        if (mc.getEnableLog() && httpResponse != null) {
            Header responseTimeHeader = httpResponse.getFirstHeader(RESPONSE_TIME_REPLY);
            if (responseTimeHeader != null && StringUtils.isNotBlank(responseTimeHeader.getValue())) {
                long resIAT = getResponseIssuedAtTime(responseTimeHeader.getValue());
                if (resIAT > 0) {
                    logger.log(Logger.LT_INFO, "responseTransitTimeSec : " + getResponseTransitTime(resIAT));
                }
            }
            List<Header> respHeaders = Arrays.asList(httpResponse.getAllHeaders());
            logger.log(Logger.LT_INFO, "Response Headers: " + respHeaders);
        }
    }

    /**
     * Conver Document to String
     *
     * @param request
     * @return
     * @throws IOException
     * @throws TransformerException
     */
    private String documentToString(Document request) throws IOException, TransformerException {
        ByteArrayOutputStream baos = null;
        try {
            baos = makeStream(request);
            return baos.toString("utf-8");
        } finally {
            if (baos != null) {
                baos.close();
            }
        }
    }

    /**
     * A custom retry handler which will retry the transaction if request is not sent or in case of connection reset and
     * NoHttpResponse Exception.
     *
     * CustomRetryHandler will be enabled only if allowRetry property is set to true. retryInterval and numberOfRetries are also config based
     * See README for more information.
     */
    private class CustomRetryHandler implements HttpRequestRetryHandler {
        long retryWaitInterval = mc.getRetryInterval();
        int maxRetries = mc.getNumberOfRetries();

        @Override
        public boolean retryRequest(IOException exception, int executionCount, HttpContext httpContext) {
            if (executionCount > maxRetries) {
                return false;
            }

            HttpClientContext httpClientContext = HttpClientContext.adapt(httpContext);
            if (!httpClientContext.isRequestSent()) {
                retryAfter(retryWaitInterval, executionCount, "request_not_sent");
                return true;
            }

            if(mc.retryIfMTIFieldExistEnabled()){
                if (exception instanceof NoHttpResponseException) {
                    retryAfter(retryWaitInterval, executionCount, "NoHttpResponseException");
                    return true;
                }
                if(exception instanceof java.net.SocketException) {
                    String errMessage = exception.getMessage();
                    if (StringUtils.isBlank(errMessage)) {
                        errMessage = exception.getLocalizedMessage();
                    }
                    if (StringUtils.isNotBlank(errMessage) && ( errMessage.equalsIgnoreCase("Connection reset") || errMessage.contains("Connection reset"))) {
                        retryAfter(retryWaitInterval, executionCount, "SocketException:Connection reset");
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Set proxy by using proxy credentials to create httpclient
     *
     * @param httpClientBuilder
     * @param requestConfigBuilder
     * @param merchantConfig
     */
    private void setProxy(HttpClientBuilder httpClientBuilder, RequestConfig.Builder requestConfigBuilder, MerchantConfig merchantConfig) {
        if (merchantConfig.getProxyHost() != null) {
            HttpHost proxy = new HttpHost(merchantConfig.getProxyHost(), merchantConfig.getProxyPort());
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
            httpClientBuilder.setRoutePlanner(routePlanner);

            if (merchantConfig.getProxyUser() != null) {
                httpClientBuilder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy());
                requestConfigBuilder.setProxyPreferredAuthSchemes(Collections.singletonList(AuthSchemes.BASIC));

                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(merchantConfig.getProxyUser(), merchantConfig.getProxyPassword()));

                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
        }
    }

    private void retryAfter(long millis, int executionCount, String reason) {
        try {
            Thread.sleep(millis);
            logger.log(Logger.LT_INFO, "Retrying Request due to " + reason +"-- Retry Count -- " + executionCount);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
}
