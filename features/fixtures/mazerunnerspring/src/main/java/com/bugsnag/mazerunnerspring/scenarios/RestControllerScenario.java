package com.bugsnag.mazerunnerspring.scenarios;

import com.bugsnag.mazerunnerspring.Scenario;
import org.springframework.web.client.RestTemplate;

/**
 * Causes an unhandled exception in the rest controller
 */
public class RestControllerScenario extends Scenario {
    @Override
    public void run() {
        final String uri = "http://localhost:1234/send-unhandled-exception";

        try {
            RestTemplate restTemplate = new RestTemplate();
            String result = restTemplate.getForObject(uri, String.class);
        } catch (Exception ex) {
            // ignore
        }
    }
}
