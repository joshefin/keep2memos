package xyz.joshefin.keep2memos;

import java.math.BigInteger;
import java.security.SecureRandom;

public class Utils {

	private static final SecureRandom SECURE_RANDOM_GENERATOR = new SecureRandom();

	public static String randomToken() {
		return new BigInteger(130, SECURE_RANDOM_GENERATOR).toString(32);
	}

}
