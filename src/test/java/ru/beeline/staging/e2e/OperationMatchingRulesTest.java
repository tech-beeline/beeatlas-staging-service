package ru.beeline.staging.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.beeline.staging.client.ProductServiceClient;
import ru.beeline.staging.product.dto.search.MatchedArchOperation;
import ru.beeline.staging.product.dto.search.OperationMatchCandidate;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationMatchingRulesTest {

    enum Source { ARCH, DISCOVERED }

    record CatalogOperation(String name, String type, String productAlias, String containerCode,
                            String branch, Source source) {

        static CatalogOperation arch(String name, String type) {
            return new CatalogOperation(name, type, "fdmshowcaseapp", "ext_container_product", "main", Source.ARCH);
        }

        CatalogOperation inProduct(String productAlias, String containerCode) {
            return new CatalogOperation(name, type, productAlias, containerCode, branch, source);
        }

        CatalogOperation onBranch(String branch) {
            return new CatalogOperation(name, type, productAlias, containerCode, branch, source);
        }

        CatalogOperation discovered() {
            return new CatalogOperation(name, type, productAlias, null, null, Source.DISCOVERED);
        }
    }

    record Scenario(String title, List<CatalogOperation> catalog, String productCode, String method,
                    String path, String discoveredContext, boolean matches) {

        @Override
        public String toString() {
            return title;
        }
    }

    static Stream<Scenario> scenarios() {
        CatalogOperation productByCode = CatalogOperation.arch("/api/v1/product/{code}", "GET");
        return Stream.of(
                new Scenario("01 путь и тип совпадают посимвольно",
                        List.of(productByCode), "fdmshowcaseapp", "GET", "/api/v1/product/{code}", null, true),
                new Scenario("02 другое имя path-параметра: {cmdb} против {code}",
                        List.of(productByCode), "fdmshowcaseapp", "GET", "/api/v1/product/{cmdb}", null, false),
                new Scenario("03 конкретное значение против шаблона каталога",
                        List.of(productByCode), "fdmshowcaseapp", "GET", "/api/v1/product/123", null, false),
                new Scenario("04 шаблон вызова против литерала каталога",
                        List.of(CatalogOperation.arch("/api/v1/product/current", "GET")),
                        "fdmshowcaseapp", "GET", "/api/v1/product/{id}", null, false),
                new Scenario("05 разное число сегментов",
                        List.of(productByCode), "fdmshowcaseapp", "GET", "/api/v1/product", null, false),
                new Scenario("06 слэш в конце пути",
                        List.of(productByCode), "fdmshowcaseapp", "GET", "/api/v1/product/{code}/", null, false),
                new Scenario("07 другой регистр пути",
                        List.of(productByCode), "fdmshowcaseapp", "GET", "/API/V1/Product/{code}", null, true),
                new Scenario("08 подчёркивание в пути как LIKE-джокер",
                        List.of(CatalogOperation.arch("/api/v1/dpXchannelXprovider/getXtp", "GET")),
                        "fdmshowcaseapp", "GET", "/api/v1/dp_channel_provider/get_tp", null, true),
                new Scenario("09 процент в пути как LIKE-джокер",
                        List.of(CatalogOperation.arch("/api/v1/product/{code}/source", "GET")),
                        "fdmshowcaseapp", "GET", "/api/v1/product/%", null, true),
                new Scenario("10 тип операции в каталоге не заполнен",
                        List.of(CatalogOperation.arch("/api/v1/product/{code}", null)),
                        "fdmshowcaseapp", "GET", "/api/v1/product/{code}", null, false),
                new Scenario("11 тип операции в каталоге UNKNOWN",
                        List.of(CatalogOperation.arch("/api/v1/product/{code}", "UNKNOWN")),
                        "fdmshowcaseapp", "GET", "/api/v1/product/{code}", null, false),
                new Scenario("12 тип в каталоге в нижнем регистре",
                        List.of(CatalogOperation.arch("/api/v1/product/{code}", "get")),
                        "fdmshowcaseapp", "GET", "/api/v1/product/{code}", null, true),
                new Scenario("13 метод вызова не совпадает с типом каталога",
                        List.of(productByCode), "fdmshowcaseapp", "POST", "/api/v1/product/{code}", null, false),
                new Scenario("14 операция принадлежит другому продукту",
                        List.of(CatalogOperation.arch("/api/v1/order", "POST").inProduct("vega", "vega_iam"),
                                CatalogOperation.arch("/api/v1/other", "GET")),
                        "fdmshowcaseapp", "POST", "/api/v1/order", null, false),
                new Scenario("15 операция в другом контейнере того же продукта",
                        List.of(CatalogOperation.arch("/api/v1/order", "POST")
                                .inProduct("fdmshowcaseapp", "other_container")),
                        "fdmshowcaseapp", "POST", "/api/v1/order", null, true),
                new Scenario("16 продукт участника не найден по alias",
                        List.of(CatalogOperation.arch("getServiceList", "GET")
                                .inProduct("napiproxy.glassfish", null)),
                        "ext_napiproxy", "GET", "getServiceList", null, false),
                new Scenario("17 операция вне ветки main",
                        List.of(productByCode.onBranch("release-1")),
                        "fdmshowcaseapp", "GET", "/api/v1/product/{code}", null, false),
                new Scenario("18 операция есть только среди discovered",
                        List.of(CatalogOperation.arch("/api/v1/product/{code}", "GET").discovered()),
                        "fdmshowcaseapp", "GET", "/api/v1/product/{code}", null, false),
                new Scenario("19 discovered-заглушка пайплайна",
                        List.of(CatalogOperation.arch("/api/v1/product/{cmdb}", "GET").discovered()),
                        "fdmshowcaseapp", "GET", "/api/v1/product/{cmdb}", null, false),
                new Scenario("20 имя операции склеивается с контекстом",
                        List.of(CatalogOperation.arch("/api/v1/product/{code}/workspace", "PATCH")),
                        "fdmshowcaseapp", "PATCH", "/workspace", "/api/v1/product/{code}", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("Валидация e2e puml идёт по тому же правилу, что сопоставление мапика")
    void appliesTheLandscapeMatchingRule(Scenario scenario) {
        assertThat(LandscapeMatchingMirror.matches(scenario))
                .as("правило fdm-products: " + scenario.title())
                .isEqualTo(scenario.matches());
        assertThat(lookupVerdict(scenario))
                .as("валидатор staging: " + scenario.title())
                .isEqualTo(scenario.matches());
    }

    private static boolean lookupVerdict(Scenario scenario) {
        ProductServiceClient client = mock(ProductServiceClient.class);
        when(client.searchMatchedOperations(anyList())).thenAnswer(invocation -> {
            List<OperationMatchCandidate> candidates = invocation.getArgument(0);
            OperationMatchCandidate candidate = candidates.get(0);
            return LandscapeMatchingMirror.matches(scenario.catalog(), candidate.getProductCode(),
                    candidate.getMethodName(), candidate.getMethodType(), scenario.discoveredContext())
                    ? List.of(matchedResponse(candidate))
                    : List.of();
        });
        return new FdmProductsRestEndpointLookup(client)
                .exists(scenario.productCode(), scenario.method(), scenario.path());
    }

    private static MatchedArchOperation matchedResponse(OperationMatchCandidate candidate) {
        MatchedArchOperation match = new MatchedArchOperation();
        match.setName(candidate.getMethodName());
        match.setType(candidate.getMethodType());
        match.setProductCode(candidate.getProductCode());
        MatchedArchOperation.Ref interfaceRef = new MatchedArchOperation.Ref();
        interfaceRef.setCode("ext_product-api");
        match.setInterfaceObj(interfaceRef);
        return match;
    }

    static final class LandscapeMatchingMirror {

        private static final String DEFAULT_BRANCH = "main";

        static boolean matches(Scenario scenario) {
            return matches(scenario.catalog(), scenario.productCode(), scenario.path(), scenario.method(),
                    scenario.discoveredContext());
        }

        static boolean matches(List<CatalogOperation> catalog, String productCode, String name, String type,
                String context) {
            if (productCode == null || catalog.stream()
                    .noneMatch(operation -> productCode.equalsIgnoreCase(operation.productAlias()))) {
                return false;
            }
            List<CatalogOperation> scope = catalog.stream()
                    .filter(operation -> operation.source() == Source.ARCH)
                    .filter(operation -> DEFAULT_BRANCH.equalsIgnoreCase(operation.branch()))
                    .filter(operation -> productCode.equalsIgnoreCase(operation.productAlias()))
                    .toList();
            return attempt(scope, name, type)
                    || attempt(scope, context == null ? null : context + name, type);
        }

        private static boolean attempt(List<CatalogOperation> scope, String name, String type) {
            if (name == null || name.isBlank() || type == null || type.isBlank()) {
                return false;
            }
            return scope.stream().anyMatch(operation -> Ilike.matches(operation.name(), name)
                    && Ilike.matches(operation.type(), type));
        }
    }

    static final class Ilike {

        static boolean matches(String value, String pattern) {
            return value != null && Pattern.compile(toRegex(pattern), Pattern.CASE_INSENSITIVE)
                    .matcher(value).matches();
        }

        private static String toRegex(String pattern) {
            StringBuilder regex = new StringBuilder();
            for (char symbol : pattern.toCharArray()) {
                switch (symbol) {
                    case '%' -> regex.append(".*");
                    case '_' -> regex.append('.');
                    default -> regex.append(Pattern.quote(String.valueOf(symbol)));
                }
            }
            return regex.toString();
        }
    }
}
