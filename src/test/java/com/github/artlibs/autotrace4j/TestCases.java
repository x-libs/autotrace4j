package com.github.artlibs.autotrace4j;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.github.artlibs.autotrace4j.context.AutoTraceCtx;
import com.github.artlibs.testcase.Injected;
import com.github.artlibs.testcase.ScheduledCase;
import com.github.artlibs.testcase.TupleResult;
import net.bytebuddy.agent.ByteBuddyAgent;
import okhttp3.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.artlibs.testcase.XxlJobCase.*;

public class TestCases {
    private final String initTraceId = "init-trace-id";
    private final String initSpanId = "init-span-id";
    private final String initParentSpanId = "init-p-span-id";
    private final String httpBinOrgUrl = "https://httpbin.org/get";
    private static final String ATO_TRACE_ID = "X-Ato-Trace-Id";
    private static final String ATO_SPAN_ID = "X-Ato-Span-Id";
    private static final String ATO_PARENT_SPAN_ID = "X-Ato-P-Span-ID";

    @BeforeAll
    public static void beforeAll() throws Exception {
        System.out.println("====== beforeAll ======");
        try {
            AutoTrace4j.premain("com.github.artlibs.testcase"
                    , ByteBuddyAgent.install());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @BeforeEach
    public void beforeEach() {
        AutoTraceCtx.setTraceId(initTraceId);
        AutoTraceCtx.setSpanId(initSpanId);
        AutoTraceCtx.setParentSpanId(initParentSpanId);
    }

    @AfterEach
    public void afterEach() {
        AutoTraceCtx.removeAll();
    }

    @Test
    void testXxlJobHandler() {
        for (XxlJobCaseRunner runner : Arrays.asList(
                new BeforeV212(),
                new AfterV212Case1(),
                new AfterV212Case2(),
                new AfterV212Case3(),
                new AfterV230Case1(),
                new AfterV230Case2())) {
            // 01.Prepare
            this.beforeEach();

            // 02.When
            runner.trigger();

            // 03.Verify
            TupleResult tuple = ((Injected)runner).getInjected();
            // expected a new traceId, spanId, and no parent span id
            Assertions.assertNotEquals(initTraceId, tuple.getValue1());
            Assertions.assertNotEquals(initSpanId, tuple.getValue2());
            Assertions.assertNull(tuple.getValue3());
        }
    }

    @Test
    void testSpringScheduled() {
        // 01.Prepare
        ScheduledCase c = new ScheduledCase();

        // 02.When
        c.runScheduleTask();

        // 03.Verify
        TupleResult tuple = c.getInjected();
        // expected a new traceId, spanId, and no parent span id
        Assertions.assertNotEquals(initTraceId, tuple.getValue1());
        Assertions.assertNotEquals(initSpanId, tuple.getValue2());
        Assertions.assertNull(tuple.getValue3());
    }

    @Test
    void testSlf4jMDC() {
        // 01.Prepare & 02.When
        String realTraceId = MDC.get(ATO_TRACE_ID);
        String realSpanId = MDC.get(ATO_SPAN_ID);
        String realParentSpanId = MDC.get(ATO_PARENT_SPAN_ID);

        // 03.Verify
        Assertions.assertEquals(initTraceId, realTraceId);
        Assertions.assertEquals(initSpanId, realSpanId);
        Assertions.assertEquals(initParentSpanId, realParentSpanId);
    }

    @Test
    void testThreadPoolExecutor() throws Exception {
        // 01.Prepare
        ExecutorService threadPoolExecutor = Executors.newFixedThreadPool(5);
        ExecutorService scheduledExecutor = Executors.newScheduledThreadPool(5);
        final String mainThreadId = String.valueOf(Thread.currentThread().getId());

        for (ExecutorService service : Arrays.asList(threadPoolExecutor, scheduledExecutor)) {
            this.beforeEach();

            // 02.When
            TupleResult result = service.submit(() -> new TupleResult(
                    AutoTraceCtx.getTraceId(),
                    AutoTraceCtx.getSpanId(),
                    AutoTraceCtx.getParentSpanId(),
                    String.valueOf(Thread.currentThread().getId()))
            ).get();

            // 03.Verify
            Assertions.assertNotNull(result);
            // expected that the traceId is the same
            Assertions.assertEquals(initTraceId, result.getValue1());
            // expected that the spanId is not null and to be a new one
            Assertions.assertNotNull(result.getValue2());
            Assertions.assertNotEquals(initSpanId, result.getValue2());
            // expected that the spanId is equals to the parent span id
            Assertions.assertNotNull(result.getValue3());
            Assertions.assertEquals(initSpanId, result.getValue3());
            // expected that run in the different thread context
            Assertions.assertNotEquals(mainThreadId, result.getValue3());
        }
    }

    @Test
    void testHttpURLConnection() throws Exception {
        // 01.Prepare
        URL url = new URL(httpBinOrgUrl);

        // 02.When
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int responseCode = conn.getResponseCode();
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        System.out.println("SunHttp out: " + response);
        TupleResult header = extractTraceIdFromHeader(response.toString());

        // 03.Verify
        Assertions.assertEquals(200, responseCode);
        Assertions.assertEquals(initTraceId, header.getValue1());
        Assertions.assertEquals(initSpanId, header.getValue2());
        Assertions.assertNull(header.getValue3());
    }

    @Test
    void testOkHttpClientAsync() throws Exception {
        // 01.Prepare
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(httpBinOrgUrl).build();
        final TupleResult[] asyncTupleHolder = new TupleResult[1];
        final Response[] responseHolder = new Response[1];
        CountDownLatch latch = new CountDownLatch(1);

        // 02.When
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                System.err.println(e.getMessage());
                asyncTupleHolder[0] = new TupleResult(
                        AutoTraceCtx.getTraceId(), AutoTraceCtx.getSpanId(),
                        AutoTraceCtx.getParentSpanId(),
                        String.valueOf(Thread.currentThread().getId())
                );
                latch.countDown();
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response resp) {
                asyncTupleHolder[0] = new TupleResult(
                        AutoTraceCtx.getTraceId(), AutoTraceCtx.getSpanId(),
                        AutoTraceCtx.getParentSpanId(),
                        String.valueOf(Thread.currentThread().getId())
                );
                responseHolder[0] = resp;
                latch.countDown();
            }
        });
        latch.await();

        Response response = responseHolder[0];
        Assertions.assertNotNull(response);
        TupleResult trans = asyncTupleHolder[0];
        boolean success = response.isSuccessful();
        ResponseBody body = response.body();
        String resp = Objects.requireNonNull(body).string();
        System.out.println("OkHttp async out: " + resp);
        TupleResult header = extractTraceIdFromHeader(resp);

        // 03.Verify
        Assertions.assertTrue(success);
        Assertions.assertEquals(initTraceId, header.getValue1());
        Assertions.assertEquals(initSpanId, header.getValue2());
        Assertions.assertNull(header.getValue3());
        Assertions.assertNotNull(trans);
        Assertions.assertEquals(initTraceId, trans.getValue1());
        Assertions.assertNotEquals(initSpanId, trans.getValue2());
        Assertions.assertEquals(initTraceId, trans.getValue1());
        // as callback is async, we expect that the parent spanId
        // is equals to the span id which we had set in the beginning
        Assertions.assertEquals(initSpanId, trans.getValue3());
        // we expected that the callback runs in a different thread context
        Assertions.assertNotEquals(String.valueOf(Thread.currentThread().getId())
                , trans.getValue4());
    }

    @Test
    void testOkHttpClientSync() throws Exception {
        // 01.Prepare
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(httpBinOrgUrl).build();

        // 02.When
        Response response = client.newCall(request).execute();
        boolean success = response.isSuccessful();
        ResponseBody body = response.body();
        String resp = Objects.requireNonNull(body).string();
        System.out.println("OkHttp sync out: " + resp);
        TupleResult header = extractTraceIdFromHeader(resp);

        // 03.Verify
        Assertions.assertTrue(success);
        Assertions.assertEquals(initTraceId, header.getValue1());
        Assertions.assertEquals(initSpanId, header.getValue2());
        Assertions.assertNull(header.getValue3());
    }

    @Test
    void testApacheHttpClient() throws Exception {
        // 01.Prepare
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(httpBinOrgUrl);

        // 02.When
        CloseableHttpResponse respObj = httpClient.execute(httpGet);
        String response = EntityUtils.toString(respObj.getEntity());
        System.out.println("ApacheHttp out: " + response);
        TupleResult header = extractTraceIdFromHeader(response);

        // 03.Verify
        Assertions.assertEquals(initTraceId, header.getValue1());
        Assertions.assertEquals(initSpanId, header.getValue2());
        Assertions.assertNull(header.getValue3());
        Assertions.assertEquals(200, respObj.getStatusLine().getStatusCode());
    }

    private TupleResult extractTraceIdFromHeader(String response) {
        JSONObject headerObj = JSON.parseObject(response)
                .getJSONObject("headers");
        Map<String, String> headerMap = new HashMap<>();
        for (String key : headerObj.keySet()) {
            headerMap.put(key.toLowerCase(), headerObj.getString(key));
        }
        return new TupleResult(
                headerMap.get(ATO_TRACE_ID.toLowerCase()),
                headerMap.get(ATO_SPAN_ID.toLowerCase())
        );
    }
}
