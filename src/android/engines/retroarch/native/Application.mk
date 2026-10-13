APP_PLATFORM := android-29
APP_STL := c++_static
APP_OPTIM := release
# Use response files for the cores' long compiler/linker command lines.
APP_SHORT_COMMANDS := true
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
APP_LDFLAGS += -Wl,-z,max-page-size=16384
