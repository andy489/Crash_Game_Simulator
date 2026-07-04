package com.crash.web;

import com.crash.model.SimulationConfig;
import com.crash.service.SimulationService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final SimulationConfig defaults;
    private final SimulationService simulationService;

    public SimulationController(SimulationConfig defaults, SimulationService simulationService) {
        this.defaults = defaults;
        this.simulationService = simulationService;
    }

    @GetMapping("/")
    public String index(Model model) {
        log.info("GET / — serving configuration form");
        model.addAttribute("config", defaults);
        return "index";
    }

    @GetMapping("/simulate/validate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> validate(@ModelAttribute SimulationRequest req) {
        log.info("GET /simulate/validate — spins={} threads={} stake={}", req.getSpins(), req.getThreadCount(), req.getStake());
        List<String> errors = SimulationValidator.validate(req);
        if (errors.isEmpty()) {
            log.info("Validation passed");
            return ResponseEntity.ok(Map.of("valid", true));
        }
        log.info("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(Map.of("valid", false, "errors", errors));
    }

    @GetMapping("/simulate/stream")
    @ResponseBody
    public SseEmitter stream(@ModelAttribute SimulationRequest req) {
        log.info("GET /simulate/stream — spins={} threads={} stake={} zeroInterval={}",
                req.getSpins(), req.getThreadCount(), req.getStake(), req.getZeroSpinInterval());
        return simulationService.run(req);
    }
}
