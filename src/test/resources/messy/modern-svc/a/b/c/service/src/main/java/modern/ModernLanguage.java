package modern;

sealed interface Command permits Create, Delete {}
record Create(long id) implements Command {}
record Delete(long id) implements Command {}

class ModernLanguage {
    String render(Command command) {
        var prefix = """
                command:
                """;
        return prefix + switch (command) {
            case Create(var id) -> "create " + id;
            case Delete(var id) -> "delete " + id;
        };
    }
}
