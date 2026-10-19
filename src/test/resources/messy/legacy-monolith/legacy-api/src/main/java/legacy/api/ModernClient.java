package legacy.api;

import legacy.shared.*;

@FeignClient(name = ServiceNames.MODERN)
public interface ModernClient extends SharedModernApi {}
