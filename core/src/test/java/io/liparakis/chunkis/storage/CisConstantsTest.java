package io.liparakis.chunkis.storage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CisConstants} to ensure utility class cannot be
 * instantiated.
 */
class CisConstantsTest {

    @Test
    void constructor_throwsAssertionError() throws Exception {
        Constructor<CisConstants> constructor = CisConstants.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class)
                .cause()
                .hasMessage("CisConstants is a utility class and should not be instantiated");
    }

    @Test
    void constants_haveExpectedValues() {
        // Verify critical constants
        assertThat(CisConstants.MAGIC).isEqualTo(0x43495334); // "CIS4"
        assertThat(CisConstants.VERSION).isEqualTo(7);
        assertThat(CisConstants.SECTION_SIZE).isEqualTo(16);
        assertThat(CisConstants.MIN_SECTION_Y).isEqualTo(-4);
        assertThat(CisConstants.MAX_SECTION_Y).isEqualTo(19);
    }
}
