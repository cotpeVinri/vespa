// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi;

import com.yahoo.application.container.JDisc;
import com.yahoo.application.container.handler.Request;
import com.yahoo.application.container.handler.Response;
import com.yahoo.component.ComponentSpecification;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.container.http.filter.FilterChainRepository;
import com.yahoo.jdisc.http.filter.SecurityRequestFilter;
import com.yahoo.jdisc.http.filter.SecurityRequestFilterChain;
import com.yahoo.slime.JsonFormat;
import com.yahoo.slime.JsonParseException;
import com.yahoo.slime.SlimeUtils;
import com.yahoo.test.json.JsonTestHelper;
import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.ApplicationAction;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.AthenzClientFactoryMock;
import com.yahoo.vespa.hosted.controller.integration.ServiceRegistryMock;
import org.junit.ComparisonFailure;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Provides testing of JSON container responses
 * 
 * @author bratseth
 */
public class ContainerTester {

    private static final boolean writeResponses = true;


    private final JDisc container;
    private final String responseFilePath;
    
    public ContainerTester(JDisc container, String responseFilePath) {
        this.container = container;
        this.responseFilePath = responseFilePath;
    }

    public Controller controller() {
        return (Controller) container.components().getComponent(Controller.class.getName());
    }

    public AthenzClientFactoryMock athenzClientFactory() {
        return (AthenzClientFactoryMock) container.components().getComponent(AthenzClientFactoryMock.class.getName());
    }

    public InMemoryFlagSource flagSource() {
        return (InMemoryFlagSource) container.components().getComponent(InMemoryFlagSource.class.getName());
    }

    public ServiceRegistryMock serviceRegistry() {
        return (ServiceRegistryMock) container.components().getComponent(ServiceRegistryMock.class.getName());
    }

    public void authorize(AthenzDomain tenantDomain, AthenzIdentity identity, ApplicationAction action, ApplicationName application) {
        athenzClientFactory().getSetup()
                .domains.get(tenantDomain)
                .applications.get(new com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId(application.value()))
                             .addRoleMember(action, identity);
    }

    public void assertJsonResponse(Supplier<Request> request, File responseFile) {
        assertResponse(request.get(), responseFile, 200, false, true);
    }

    public void assertResponse(Supplier<Request> request, File responseFile) {
        assertResponse(request.get(), responseFile);
    }

    public void assertResponse(Request request, File responseFile) {
        assertResponse(request, responseFile, 200);
    }

    public void assertResponse(Supplier<Request> request, File responseFile, int expectedStatusCode) {
        assertResponse(request.get(), responseFile, expectedStatusCode);
    }

    public void assertResponse(Request request, File responseFile, int expectedStatusCode) {
        assertResponse(request, responseFile, expectedStatusCode, true);
    }

    public void assertResponse(Request request, File responseFile, int expectedStatusCode, boolean removeWhitespace) {
        assertResponse(request, responseFile, expectedStatusCode, removeWhitespace, false);
    }

    private void assertResponse(Request request, File responseFile, int expectedStatusCode, boolean removeWhitespace, boolean compareJson) {
        String expectedResponse = readTestFile(responseFile.toString());
        expectedResponse = include(expectedResponse);
        FilterResult filterResult = invokeSecurityFilters(request);
        request = filterResult.request;
        Response response = filterResult.response != null ? filterResult.response : container.handleRequest(request);
        String responseString;
        try {
            responseString = response.getBodyAsString();
        }
        catch (CharacterCodingException e) {
            throw new UncheckedIOException(e);
        }
        try {
            if (responseFilePath.endsWith(".json")) {
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                new JsonFormat(false).encode(expected, SlimeUtils.jsonToSlimeOrThrow(expectedResponse));
                new JsonFormat(false).encode(actual, SlimeUtils.jsonToSlimeOrThrow(responseString));
                if (writeResponses) writeTestFile(responseFilePath, expected.toByteArray());
                else assertEquals(expected.toString(UTF_8), actual.toString(UTF_8));
            }
            else{ // Not JSON? Let's do a verbatim comparison, then ...
                if (writeResponses) writeTestFile(responseFilePath, expectedResponse.getBytes(UTF_8));
                else assertEquals(expectedResponse, responseString);
            }
        }
        catch (IOException e) {
            fail("failed writing JSON: " + e);
        }
        assertEquals("Status code", expectedStatusCode, response.getStatus());
    }

    public void assertResponse(Supplier<Request> request, String expectedResponse) {
        assertResponse(request, expectedResponse, 200);
    }

    public void assertResponse(Request request, String expectedResponse) {
        assertResponse(() -> request, expectedResponse, 200);
    }

    public void assertResponse(Request request, String expectedResponse, int expectedStatusCode) {
        assertResponse(() -> request, expectedResponse, expectedStatusCode);
    }

    public void assertResponse(Supplier<Request> request, String expectedResponse, int expectedStatusCode) {
        assertResponse(request,
                       (response) -> assertEquals(expectedResponse, new String(response.getBody(), UTF_8)),
                       expectedStatusCode);
    }

    public void assertResponse(Supplier<Request> requestSupplier, ConsumerThrowingException<Response> responseAssertion, int expectedStatusCode) {
        var request = requestSupplier.get();
        FilterResult filterResult = invokeSecurityFilters(request);
        request = filterResult.request;
        Response response = filterResult.response != null ? filterResult.response : container.handleRequest(request);
        try {
            responseAssertion.accept(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("Status code", expectedStatusCode, response.getStatus());
    }

    // Hack to run request filters as part of the request processing chain.
    // Limitation: Bindings ignored, disc filter request wrapper only support limited set of methods.
    private FilterResult invokeSecurityFilters(Request request) {
        FilterChainRepository filterChainRepository = (FilterChainRepository) container.components().getComponent(FilterChainRepository.class.getName());
        SecurityRequestFilterChain chain = (SecurityRequestFilterChain) filterChainRepository.getFilter(ComponentSpecification.fromString("default"));
        for (SecurityRequestFilter securityRequestFilter : chain.getFilters()) {
            ApplicationRequestToDiscFilterRequestWrapper discFilterRequest = new ApplicationRequestToDiscFilterRequestWrapper(request);
            ResponseHandlerToApplicationResponseWrapper responseHandlerWrapper = new ResponseHandlerToApplicationResponseWrapper();
            securityRequestFilter.filter(discFilterRequest, responseHandlerWrapper);
            request = discFilterRequest.getUpdatedRequest();
            Optional<Response> filterResponse = responseHandlerWrapper.toResponse();
            if (filterResponse.isPresent()) {
                return new FilterResult(request, filterResponse.get());
            }
        }
        return new FilterResult(request, null);
    }

    /** Replaces @include(localFile) with the content of the file */
    private String include(String response) {
        // Please don't look at this code
        int includeIndex = response.indexOf("@include(");
        if (includeIndex < 0) return response;
        String prefix = response.substring(0, includeIndex);
        String rest = response.substring(includeIndex + "@include(".length());
        int filenameEnd = rest.indexOf(")");
        String includeFileName = rest.substring(0, filenameEnd);
        String includedContent = readTestFile(includeFileName);
        includedContent = include(includedContent);
        String postFix = rest.substring(filenameEnd + 1);
        postFix = include(postFix);
        return prefix + includedContent + postFix;
    }

    private void writeTestFile(String name, byte[] content) {
        try {
            Files.write(Paths.get(responseFilePath, name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readTestFile(String name) {
        try {
            return new String(Files.readAllBytes(Paths.get(responseFilePath, name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class FilterResult {
        final Request request;
        final Response response;

        FilterResult(Request request, Response response) {
            this.request = request;
            this.response = response;
        }
    }

    @FunctionalInterface
    public interface ConsumerThrowingException<T> {
        void accept(T t) throws Exception;
    }
}