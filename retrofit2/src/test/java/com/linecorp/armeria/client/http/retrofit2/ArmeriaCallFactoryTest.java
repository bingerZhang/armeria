/*
 * Copyright (c) 2016 LINE Corporation. All rights reserved.
 * LINE Corporation PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.linecorp.armeria.client.http.retrofit2;

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.ROUND_ROBIN;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.internal.http.ArmeriaHttpUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.handler.codec.http.QueryStringDecoder;
import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.adapter.java8.Java8CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Url;

public class ArmeriaCallFactoryTest {
    public static class Pojo {
        @JsonProperty("name")
        String name;
        @JsonProperty("age")
        int age;

        @JsonCreator
        public Pojo(@JsonProperty("name") String name, @JsonProperty("age") int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Pojo)) {
                return false;
            }
            Pojo other = (Pojo) o;
            return name.equals(other.name) && age == other.age;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = result * 31 + (name == null ? 43 : name.hashCode());
            result = result * 31 + age;
            return result;
        }

        @Override
        public String toString() {
            return "Pojo[name=" + name + ", age=" + age + ']';
        }
    }

    interface Service {

        @GET("/pojo")
        CompletableFuture<Pojo> pojo();

        @GET("/pojo")
        Call<Pojo> pojoReturnCall();

        @GET("/pojos")
        CompletableFuture<List<Pojo>> pojos();

        @GET("/queryString")
        CompletableFuture<Pojo> queryString(@Query("name") String name, @Query("age") int age);

        @GET("/queryString")
        CompletableFuture<Pojo> queryStringEncoded(@Query(value = "name", encoded = true) String name,
                                                   @Query("age") int age);

        @POST("/post")
        @Headers("content-type: application/json; charset=UTF-8")
        CompletableFuture<Response<Void>> post(@Body Pojo pojo);

        @POST("/postForm")
        @FormUrlEncoded
        CompletableFuture<Response<Pojo>> postForm(@Field("name") String name,
                                                   @Field("age") int age);

        @POST("/postForm")
        @FormUrlEncoded
        CompletableFuture<Response<Pojo>> postFormEncoded(@Field(value = "name", encoded = true) String name,
                                                          @Field("age") int age);

        @POST("/postCustomContentType")
        CompletableFuture<Response<Void>> postCustomContentType(@Header("Content-Type") String contentType);

        @GET
        CompletableFuture<Pojo> fullUrl(@Url String url);

        @GET("pojo")
        CompletableFuture<Pojo> pojoNotRoot();

        @GET("/pathWithName/{name}")
        CompletableFuture<Pojo> customPath(@Path("name") String name, @Query("age") int age);

        @GET("{path}")
        CompletableFuture<Pojo> customPathEncoded(@Path(value = "path", encoded = true) String path);
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/pojo", new AbstractHttpService() {
                @Override
                protected void doGet(ServiceRequestContext ctx,
                                     HttpRequest req, HttpResponseWriter res) throws Exception {
                    res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                "{\"name\":\"Cony\", \"age\":26}");
                }
            })
              .serviceUnder("/pathWithName", new AbstractHttpService() {

                  @Override
                  protected void doGet(ServiceRequestContext ctx,
                                       HttpRequest req, HttpResponseWriter res) throws Exception {
                      req.aggregate().handle(voidFunction((aReq, cause) -> {
                          Map<String, List<String>> params = new QueryStringDecoder(aReq.path())
                                  .parameters();
                          String fullPath = ArmeriaHttpUtil.splitPathAndQuery(req.path())[0];
                          res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                      "{\"name\":\"" + fullPath.replace("/pathWithName/", "") + "\", " +
                                      "\"age\":" + params.get("age").get(0) + '}');
                      }));
                  }
              })
              .service("/nest/pojo", new AbstractHttpService() {
                  @Override
                  protected void doGet(ServiceRequestContext ctx,
                                       HttpRequest req, HttpResponseWriter res) throws Exception {
                      res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                  "{\"name\":\"Leonard\", \"age\":21}");
                  }
              })
              .service("/pojos", new AbstractHttpService() {
                  @Override
                  protected void doGet(ServiceRequestContext ctx,
                                       HttpRequest req, HttpResponseWriter res) throws Exception {
                      res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                  "[{\"name\":\"Cony\", \"age\":26}," +
                                  "{\"name\":\"Leonard\", \"age\":21}]");
                  }
              })
              .service("/queryString", new AbstractHttpService() {
                  @Override
                  protected void doGet(ServiceRequestContext ctx,
                                       HttpRequest req, HttpResponseWriter res) throws Exception {
                      req.aggregate().handle(voidFunction((aReq, cause) -> {
                          Map<String, List<String>> params = new QueryStringDecoder(aReq.path())
                                  .parameters();
                          res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                      "{\"name\":\"" + params.get("name").get(0) + "\", " +
                                      "\"age\":" + params.get("age").get(0) + '}');
                      }));
                  }
              })
              .service("/post", new AbstractHttpService() {
                  @Override
                  protected void doPost(ServiceRequestContext ctx,
                                        HttpRequest req, HttpResponseWriter res) throws Exception {
                      req.aggregate().handle(voidFunction((aReq, cause) -> {
                          if (cause != null) {
                              res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          Throwables.getStackTraceAsString(cause));
                              return;
                          }
                          String text = aReq.content().toStringUtf8();
                          final Pojo request;
                          try {
                              request = OBJECT_MAPPER.readValue(text, Pojo.class);
                          } catch (IOException e) {
                              res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          Throwables.getStackTraceAsString(e));
                              return;
                          }
                          assertThat(request).isEqualTo(new Pojo("Cony", 26));
                          res.respond(HttpStatus.OK);
                      }));
                  }
              })
              .service("/postForm", new AbstractHttpService() {
                  @Override
                  protected void doPost(ServiceRequestContext ctx,
                                        HttpRequest req, HttpResponseWriter res) throws Exception {
                      req.aggregate().handle(voidFunction((aReq, cause) -> {
                          if (cause != null) {
                              res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          Throwables.getStackTraceAsString(cause));
                              return;
                          }
                          Map<String, List<String>> params = new QueryStringDecoder(
                                  aReq.content().toStringUtf8(), false)
                                  .parameters();
                          res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                      "{\"name\":\"" + params.get("name").get(0) + "\", " +
                                      "\"age\":" + params.get("age").get(0) + '}');
                      }));
                  }
              })
              .service("/postCustomContentType", new AbstractHttpService() {
                  @Override
                  protected void doPost(ServiceRequestContext ctx,
                                        HttpRequest req, HttpResponseWriter res) throws Exception {
                      req.aggregate().handle(voidFunction((aReq, cause) -> {
                          if (cause != null) {
                              res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          Throwables.getStackTraceAsString(cause));
                              return;
                          }
                          Map<String, List<String>> params = new QueryStringDecoder(
                                  aReq.content().toStringUtf8(), false)
                                  .parameters();
                          assertThat(params).isEmpty();
                          res.respond(HttpStatus.OK);
                      }));
                  }
              });
        }
    };

    private Service service;

    @Before
    public void setUp() {
        service = new ArmeriaRetrofitBuilder()
                .baseUrl(server.uri("/"))
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .addCallAdapterFactory(Java8CallAdapterFactory.create())
                .build()
                .create(Service.class);
    }

    @Test
    public void pojo() throws Exception {
        Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojoNotRoot() throws Exception {
        Pojo pojo = service.pojoNotRoot().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojos() throws Exception {
        List<Pojo> pojos = service.pojos().get();
        assertThat(pojos.get(0)).isEqualTo(new Pojo("Cony", 26));
        assertThat(pojos.get(1)).isEqualTo(new Pojo("Leonard", 21));
    }

    @Test
    public void queryString() throws Exception {
        Pojo response = service.queryString("Cony", 26).get();
        assertThat(response).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void queryString_withSpecialCharacter() throws Exception {
        Pojo response = service.queryString("Foo+Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo+Bar", 33));

        response = service.queryString("Foo%2BBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo%2BBar", 33));
    }

    @Test
    public void queryStringEncoded() throws Exception {
        Pojo response = service.queryStringEncoded("Foo%2BBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo+Bar", 33));

        response = service.queryStringEncoded("Foo+Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo Bar", 33));
    }

    @Test
    public void post() throws Exception {
        Response<Void> response = service.post(new Pojo("Cony", 26)).get();
        assertThat(response.isSuccessful()).isTrue();
    }

    @Test
    public void form() throws Exception {
        assertThat(service.postForm("Cony", 26).get().body()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.postForm("Foo+Bar", 26).get().body()).isEqualTo(new Pojo("Foo+Bar", 26));
        assertThat(service.postForm("Foo%2BBar", 26).get().body()).isEqualTo(new Pojo("Foo%2BBar", 26));
    }

    @Test
    public void formEncoded() throws Exception {
        assertThat(service.postFormEncoded("Cony", 26).get().body()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.postFormEncoded("Foo+Bar", 26).get().body()).isEqualTo(new Pojo("Foo Bar", 26));
        assertThat(service.postFormEncoded("Foo%2BBar", 26).get().body()).isEqualTo(new Pojo("Foo+Bar", 26));
    }

    @Test
    public void pojo_returnCall() throws Exception {
        Pojo pojo = service.pojoReturnCall().execute().body();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojo_returnCallCancelBeforeEnqueue() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Call<Pojo> pojoCall = service.pojoReturnCall();
        pojoCall.cancel();
        pojoCall.enqueue(new Callback<Pojo>() {
            @Override
            public void onResponse(Call<Pojo> call, Response<Pojo> response) {
            }

            @Override
            public void onFailure(Call<Pojo> call, Throwable t) {
                countDownLatch.countDown();
            }
        });
        assertThat(countDownLatch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void pojo_returnCallCancelAfterComplete() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicInteger failCount = new AtomicInteger(0);
        Call<Pojo> pojoCall = service.pojoReturnCall();
        pojoCall.enqueue(new Callback<Pojo>() {
            @Override
            public void onResponse(Call<Pojo> call, Response<Pojo> response) {
                countDownLatch.countDown();
            }

            @Override
            public void onFailure(Call<Pojo> call, Throwable t) {
                failCount.incrementAndGet();
            }
        });
        assertThat(countDownLatch.await(3, TimeUnit.SECONDS)).isTrue();
        pojoCall.cancel();
        assertThat(failCount.intValue()).isZero();
    }

    @Test
    public void respectsHttpClientUri() throws Exception {
        Response<Pojo> response = service.postForm("Cony", 26).get();
        assertThat(response.raw().request().url()).isEqualTo(
                new HttpUrl.Builder().scheme("http")
                                     .host("127.0.0.1")
                                     .port(server.httpPort())
                                     .addPathSegment("postForm")
                                     .build());
    }

    @Test
    public void respectsHttpClientUri_endpointGroup() throws Exception {
        EndpointGroupRegistry.register("foo",
                                       new StaticEndpointGroup(Endpoint.of("127.0.0.1", server.httpPort())),
                                       ROUND_ROBIN);
        Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("http://group:foo/")
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .addCallAdapterFactory(Java8CallAdapterFactory.create())
                .build()
                .create(Service.class);
        Response<Pojo> response = service.postForm("Cony", 26).get();
        // TODO(ide) Use the actual `host:port`. See https://github.com/line/armeria/issues/379
        assertThat(response.raw().request().url()).isEqualTo(
                new HttpUrl.Builder().scheme("http")
                                     .host("group_foo")
                                     .addPathSegment("postForm")
                                     .build());
    }

    @Test
    public void urlAnnotation() throws Exception {
        EndpointGroupRegistry.register("bar",
                                       new StaticEndpointGroup(Endpoint.of("127.0.0.1", server.httpPort())),
                                       ROUND_ROBIN);
        Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("http://group:foo/")
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .addCallAdapterFactory(Java8CallAdapterFactory.create())
                .build()
                .create(Service.class);
        Pojo pojo = service.fullUrl("http://group_bar/pojo").get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void urlAnnotation_uriWithoutScheme() throws Exception {
        EndpointGroupRegistry.register("bar",
                                       new StaticEndpointGroup(Endpoint.of("127.0.0.1", server.httpPort())),
                                       ROUND_ROBIN);
        assertThat(service.fullUrl("//localhost:" + server.httpPort() + "/nest/pojo").get()).isEqualTo(
                new Pojo("Leonard", 21));
        assertThat(service.fullUrl("//group_bar/nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));

        assertThat(service.fullUrl("//localhost:" + server.httpPort() + "/pojo").get()).isEqualTo(
                new Pojo("Cony", 26));
        assertThat(service.fullUrl("//group_bar/pojo").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void sessionProtocolH1C() throws Exception {
        Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .addCallAdapterFactory(Java8CallAdapterFactory.create())
                .build()
                .create(Service.class);
        Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void baseUrlContainsPath() throws Exception {
        Service service = new ArmeriaRetrofitBuilder()
                .baseUrl(server.uri("/nest/"))
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .addCallAdapterFactory(Java8CallAdapterFactory.create())
                .build()
                .create(Service.class);
        assertThat(service.pojoNotRoot().get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.pojo().get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void customPath() throws Exception {
        assertThat(service.customPath("Foo", 23).get()).isEqualTo(new Pojo("Foo", 23));
        assertThat(service.customPath("Foo+Bar", 24).get()).isEqualTo(new Pojo("Foo+Bar", 24));
        assertThat(service.customPath("Foo+Bar/Hoge", 24).get()).isEqualTo(new Pojo("Foo+Bar%2FHoge", 24));
        assertThat(service.customPath("Foo%2BBar", 24).get()).isEqualTo(new Pojo("Foo%252BBar", 24));
    }

    @Test
    public void customPathEncoded() throws Exception {
        assertThat(service.customPathEncoded("/nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.customPathEncoded("nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.customPathEncoded("/pojo").get()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.customPathEncoded("pojo").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void customNewClientFunction() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .addCallAdapterFactory(Java8CallAdapterFactory.create())
                .withClientOptions((url, optionsBuilder) -> {
                    optionsBuilder.decorator(HttpRequest.class, HttpResponse.class, (delegate, ctx, req) -> {
                        counter.incrementAndGet();
                        return delegate.execute(ctx, req);
                    });
                    return optionsBuilder;
                })
                .build().create(Service.class);

        service.pojo().get();
        assertThat(counter.get()).isEqualTo(1);

        service.fullUrl("http://localhost:" + server.httpPort() + "/pojo").get();
        assertThat(counter.get()).isEqualTo(2);
    }

    /**
     * Tests https://github.com/line/armeria/pull/386
     */
    @Test
    public void nullContentType() throws Exception {
        Response<Void> response = service.postCustomContentType(null).get();
        assertThat(response.code()).isEqualTo(200);
    }
}
