package org.opentron.backend.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebSearchServiceTest {

    @Test
    void usesTavilyServiceForUiConfiguredTavilyKey() {
        SearchKeyService searchKeyService = mock(SearchKeyService.class);
        NetworkPolicyService networkPolicyService = mock(NetworkPolicyService.class);
        TavilyService tavilyService = mock(TavilyService.class);
        String query = "latest Java release";

        when(networkPolicyService.isInternetAllowed()).thenReturn(true);
        when(searchKeyService.loadKey()).thenReturn("test-key");
        when(searchKeyService.loadProvider()).thenReturn("tavily");
        when(tavilyService.search(query, 2, false, "test-key")).thenReturn(List.of(
                Map.of("type", "search_result", "title", "Release notes", "url", "https://example.test",
                        "snippet", "Latest release information")
        ));

        WebSearchService service = new WebSearchService(searchKeyService, networkPolicyService, tavilyService);

        List<Map<String, String>> results = service.search(query, 2);

        assertEquals(1, results.size());
        assertEquals("Release notes", results.get(0).get("title"));
        assertEquals("https://example.test", results.get(0).get("link"));
        verify(tavilyService).search(query, 2, false, "test-key");
    }

    @Test
    void doesNotCallExternalSearchProvidersWhenInternetIsDisabled() {
        SearchKeyService searchKeyService = mock(SearchKeyService.class);
        NetworkPolicyService networkPolicyService = mock(NetworkPolicyService.class);
        TavilyService tavilyService = mock(TavilyService.class);
        when(networkPolicyService.isInternetAllowed()).thenReturn(false);

        WebSearchService service = new WebSearchService(searchKeyService, networkPolicyService, tavilyService);

        assertTrue(service.search("local archive question", 3).isEmpty());
        verifyNoInteractions(searchKeyService, tavilyService);
    }
}
