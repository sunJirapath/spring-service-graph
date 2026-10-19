package legacy.shared;

public interface SharedModernApi extends ModernPaths {
    @GetMapping(ITEMS + "/{id}")
    Object item(long id);

    @PostMapping(ITEMS + "/{id}/archive")
    void archive(long id);
}
