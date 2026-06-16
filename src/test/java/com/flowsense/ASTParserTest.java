package com.flowsense;

import com.flowsense.model.ParsedClass;
import com.flowsense.model.ParsedMethod;
import com.flowsense.parser.ASTParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the ASTParser.
 * Run these FIRST to verify parsing works before starting Spring.
 *
 * Run with: mvn test -Dtest=ASTParserTest
 */
@DisplayName("ASTParser Tests")
class ASTParserTest {

    private ASTParser parser;

    @BeforeEach
    void setUp() {
        parser = new ASTParser();
    }

    // ── BASIC PARSING ─────────────────────────────────────────

    @Test
    @DisplayName("Should parse a simple class correctly")
    void shouldParseSimpleClass() {
        String source = """
            package com.example;
            
            public class PaymentService {
                
                private final PaymentRepository repository;
                
                public PaymentService(PaymentRepository repository) {
                    this.repository = repository;
                }
                
                public String processPayment(String amount, String currency) {
                    repository.save(amount);
                    return "processed";
                }
            }
            """;

        List<ParsedClass> classes = parser.parseSource(source, "PaymentService.java");

        assertThat(classes).hasSize(1);
        ParsedClass cls = classes.get(0);

        assertThat(cls.getClassName()).isEqualTo("PaymentService");
        assertThat(cls.getPackageName()).isEqualTo("com.example");
        assertThat(cls.getFullyQualifiedName()).isEqualTo("com.example.PaymentService");
        assertThat(cls.getMethods()).hasSize(2); // constructor + processPayment
    }

    @Test
    @DisplayName("Should extract method calls correctly")
    void shouldExtractMethodCalls() {
        String source = """
            package com.example;
            
            public class OrderService {
                
                private PaymentService paymentService;
                private InventoryService inventoryService;
                
                public void checkout(String orderId) {
                    inventoryService.reserveItems(orderId);
                    paymentService.processPayment("100", "USD");
                    notifyUser(orderId);
                }
                
                private void notifyUser(String orderId) {
                    System.out.println("Order confirmed: " + orderId);
                }
            }
            """;

        List<ParsedClass> classes = parser.parseSource(source, "OrderService.java");
        ParsedClass cls = classes.get(0);

        // Find the checkout method
        ParsedMethod checkoutMethod = cls.getMethods().stream()
            .filter(m -> m.getMethodName().equals("checkout"))
            .findFirst()
            .orElseThrow();

        // Should have found the method calls
        assertThat(checkoutMethod.getMethodCalls()).isNotEmpty();

        // Should have found calls to reserveItems, processPayment, notifyUser
        List<String> calledMethods = checkoutMethod.getMethodCalls().stream()
            .map(ParsedMethod.MethodCall::getCalleeMethod)
            .toList();

        assertThat(calledMethods).contains("reserveItems", "processPayment", "notifyUser");
    }

    @Test
    @DisplayName("Should detect interface type correctly")
    void shouldDetectInterfaceType() {
        String source = """
            package com.example;
            
            public interface PaymentGateway {
                String charge(String amount);
                void refund(String transactionId);
            }
            """;

        List<ParsedClass> classes = parser.parseSource(source, "PaymentGateway.java");
        assertThat(classes).hasSize(1);
        assertThat(classes.get(0).getClassType())
            .isEqualTo(ParsedClass.ClassType.INTERFACE);
    }

    @Test
    @DisplayName("Should extract field annotations")
    void shouldExtractFieldAnnotations() {
        String source = """
            package com.example;
            
            import org.springframework.beans.factory.annotation.Autowired;
            
            public class UserService {
                @Autowired
                private UserRepository userRepository;
                
                public void save(String user) {}
            }
            """;

        List<ParsedClass> classes = parser.parseSource(source, "UserService.java");
        ParsedClass cls = classes.get(0);

        assertThat(cls.getFields()).hasSize(1);
        assertThat(cls.getFields().get(0).getFieldName()).isEqualTo("userRepository");
        assertThat(cls.getFields().get(0).getAnnotation()).isEqualTo("@Autowired");
    }

    @Test
    @DisplayName("Should calculate cyclomatic complexity")
    void shouldCalculateCyclomaticComplexity() {
        String source = """
            package com.example;
            
            public class ComplexService {
                
                // Simple method — complexity 1
                public void simpleMethod() {
                    System.out.println("hello");
                }
                
                // Complex method — if + for + catch = complexity 4
                public void complexMethod(String input) {
                    if (input == null) {
                        return;
                    }
                    for (int i = 0; i < 10; i++) {
                        try {
                            process(i);
                        } catch (Exception e) {
                            handleError(e);
                        }
                    }
                }
                
                private void process(int i) {}
                private void handleError(Exception e) {}
            }
            """;

        List<ParsedClass> classes = parser.parseSource(source, "ComplexService.java");
        ParsedClass cls = classes.get(0);

        ParsedMethod simpleMethod = cls.getMethods().stream()
            .filter(m -> m.getMethodName().equals("simpleMethod"))
            .findFirst().orElseThrow();

        ParsedMethod complexMethod = cls.getMethods().stream()
            .filter(m -> m.getMethodName().equals("complexMethod"))
            .findFirst().orElseThrow();

        assertThat(simpleMethod.getCyclomaticComplexity()).isEqualTo(1);
        assertThat(complexMethod.getCyclomaticComplexity()).isGreaterThan(2);
    }

    @Test
    @DisplayName("Should handle malformed Java gracefully")
    void shouldHandleMalformedJavaGracefully() {
        String malformedSource = "this is not valid java { {{{ }}}";

        // Should not throw — just return empty list
        assertThatCode(() -> {
            List<ParsedClass> result = parser.parseSource(malformedSource, "Bad.java");
            assertThat(result).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should extract line numbers correctly")
    void shouldExtractLineNumbers() {
        String source = """
            package com.example;
            
            public class LineTest {
                
                public void firstMethod() {
                    System.out.println("first");
                }
                
                public void secondMethod() {
                    System.out.println("second");
                }
            }
            """;

        List<ParsedClass> classes = parser.parseSource(source, "LineTest.java");
        ParsedClass cls = classes.get(0);

        ParsedMethod first = cls.getMethods().stream()
            .filter(m -> m.getMethodName().equals("firstMethod"))
            .findFirst().orElseThrow();

        ParsedMethod second = cls.getMethods().stream()
            .filter(m -> m.getMethodName().equals("secondMethod"))
            .findFirst().orElseThrow();

        // Second method should start after first
        assertThat(second.getLineStart()).isGreaterThan(first.getLineEnd());
    }

    @Test
    @DisplayName("Should parse Spring Boot controller annotations")
    void shouldParseSpringAnnotations() {
        String source = """
            package com.example;
            
            import org.springframework.web.bind.annotation.*;
            
            @RestController
            @RequestMapping("/api/payments")
            public class PaymentController {
                
                @GetMapping("/{id}")
                public String getPayment(@PathVariable String id) {
                    return id;
                }
                
                @PostMapping
                @Transactional
                public String createPayment(@RequestBody String body) {
                    return "created";
                }
            }
            """;

        List<ParsedClass> classes = parser.parseSource(source, "PaymentController.java");
        ParsedClass cls = classes.get(0);

        // Class should have RestController annotation
        assertThat(cls.getAnnotations()).isNotEmpty();
        assertThat(cls.getAnnotations().stream()
            .anyMatch(a -> a.getName().equals("RestController"))).isTrue();

        // Methods should have their annotations
        ParsedMethod createMethod = cls.getMethods().stream()
            .filter(m -> m.getMethodName().equals("createPayment"))
            .findFirst().orElseThrow();

        assertThat(createMethod.getAnnotations()).contains("@PostMapping", "@Transactional");
    }
}
