package controller.authentication;

import java.io.*;
import java.util.*;

import org.springframework.http.*;
import org.springframework.util.*;
import org.springframework.web.bind.annotation.*;

import model.*;
import services.AuthTokenService;
import services.MailService;
import types.AuthTokens;
import static helpers.Utilities.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    // Token authentication service
    private final AuthTokenService authTokenService;

    // Mailing service
    private final MailService mailService;

    // Api host
    private final String API_HOST;

    /**
     * Initializes controller
     */
    public AuthController() throws IOException {
        authTokenService = new AuthTokenService();
        mailService = new MailService();

        Properties configProps = new Properties();
        configProps.load(new FileInputStream(ResourceUtils.getFile("classpath:properties/api.properties")));
        API_HOST = configProps.getProperty("API_HOST");
    }

    /**
     * Verifies whether the supplied credentials are valid
     *
     * @param payload JSON object containing "email", "password" fields
     * @apiNote POST request
     *
     * @return JSON object containing access and refresh tokens within new token family if success.
     *         otherwise, JSON object containing status message.
     *         200 status code iff success
     */
    @RequestMapping(path = "/login",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE,
        method = RequestMethod.POST)
    public ResponseEntity<Object> verifyUserCredentials(@RequestBody Map<String, String> payload) {

        DatabaseConnection dbconn = DatabaseConnectionPool.getConnection();

        try {
            String email = payload.get("email").toLowerCase();
            String password = payload.get("password");

            // Check that the credentials are correct
            ResponseEntity<Boolean> verifyCredentialsStatus = dbconn.transaction_verifyCredentials(email, password);
            if (Boolean.FALSE.equals(verifyCredentialsStatus.getBody())) {
                return createStatusJSON("Incorrect credentials", HttpStatus.UNAUTHORIZED);
            }

            // Check that the email has been verified
            ResponseEntity<Boolean> verifyEmailStatus = dbconn.transaction_checkEmailVerified(email);
            if (Boolean.FALSE.equals(verifyEmailStatus.getBody())) {
                return createStatusJSON("Please verify your email before logging in", HttpStatus.BAD_REQUEST);
            }

            // Get the userId for token generation
            ResponseEntity<String> resolveEmailToUserIdStatus = dbconn.transaction_resolveEmailToUserId(email);
            if (resolveEmailToUserIdStatus.getStatusCode() != HttpStatus.OK) {
                return createStatusJSON("Failed to verify credentials", HttpStatus.BAD_REQUEST);
            }

            String userId = resolveEmailToUserIdStatus.getBody();
            return new ResponseEntity<>(authTokenService.generateAccessAndRefreshTokens(dbconn, userId), HttpStatus.OK);

        } finally {
            DatabaseConnectionPool.releaseConnection(dbconn);
        }
    }

    /**
     * Updates the user's credentials
     *
     * @param payload JSON object containing "userId", "password", "newPassword" fields
     * @apiNote POST request
     *
     * @return JSON object containing access and refresh tokens within new token family if success.
     *         otherwise, JSON object containing status message.
     *         200 status code iff success
     */
    @RequestMapping(path = "/update-credentials",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE,
        method = RequestMethod.POST)
    public ResponseEntity<Object> updateUserCredentials(@RequestBody Map<String, String> payload) {

        DatabaseConnection dbconn = DatabaseConnectionPool.getConnection();

        try {
            String userId = payload.get("userId");
            String password = payload.get("password");
            String newPassword = payload.get("newPassword");

            ResponseEntity<Boolean> updateCredentialsStatus = dbconn.transaction_updateCredentials(userId, password, newPassword);
            if (Boolean.FALSE.equals(updateCredentialsStatus.getBody())) {
                return createStatusJSON("Incorrect credentials", HttpStatus.UNAUTHORIZED);
            }
            return new ResponseEntity<>(authTokenService.generateAccessAndRefreshTokens(dbconn, userId), HttpStatus.OK);

        } finally {
            DatabaseConnectionPool.releaseConnection(dbconn);
        }
    }

    /**
     * Revokes all refresh tokens. All remaining access tokens will expire within 10 minutes
     *
     * @param payload JSON object containing "userId" field
     * @apiNote POST request
     *
     * @return JSON object containing status message. 200 status code iff success
     */
    @RequestMapping(path = "/revoke-tokens",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE,
        method = RequestMethod.POST)
    public ResponseEntity<Object> revokeTokens(@RequestBody Map<String, String> payload) {

        DatabaseConnection dbconn = DatabaseConnectionPool.getConnection();

        try {
            String userId = payload.get("userId");

            if (authTokenService.revokeTokens(dbconn, userId)) {
                return createStatusJSON("All tokens revoked", HttpStatus.OK);
            } else {
                return createStatusJSON("Failed to revoke tokens", HttpStatus.INTERNAL_SERVER_ERROR);
            }

        } finally {
            DatabaseConnectionPool.releaseConnection(dbconn);
        }
    }

    /**
     * Renews access and refresh tokens given valid refresh token
     *
     * @param payload JSON object containing "userId", "refreshToken" fields
     * @apiNote POST request
     *
     * @return JSON object containing access and refresh tokens within current token family if success.
     *         otherwise, JSON object containing status message.
     *         200 status code iff success
     */
    @RequestMapping(path = "/renew-tokens",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE,
        method = RequestMethod.POST)
    public ResponseEntity<Object> renewTokens(@RequestBody Map<String, String> payload) {

        DatabaseConnection dbconn = DatabaseConnectionPool.getConnection();

        try {
            String userId = payload.get("userId");
            String refreshToken = payload.get("refreshToken");
            AuthTokens tokens = authTokenService.verifyRefreshToken(dbconn, userId, refreshToken);

            if (tokens != null) {
                return new ResponseEntity<>(tokens, HttpStatus.OK);
            } else {
                return createStatusJSON("Refresh token rejected", HttpStatus.UNAUTHORIZED);
            }

        } finally {
            DatabaseConnectionPool.releaseConnection(dbconn);
        }
    }

    /**
     * Sends a password reset email
     *
     * @param payload JSON object containing "email" field
     * @apiNote POST request
     *
     * @return JSON object containing status message. 200 status code iff success or user does not exist
     */
    @RequestMapping(path = "/send-password-reset-email",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE,
        method = RequestMethod.POST)
    public ResponseEntity<Object> sendPasswordResetEmail(@RequestBody Map<String, String> payload) {

        DatabaseConnection dbconn = DatabaseConnectionPool.getConnection();

		try {
            String email = payload.get("email").toLowerCase();

            // Gets the name of the user
            ResponseEntity<String> resolveEmailToUserNameStatus = dbconn.transaction_resolveEmailToUserName(email);
            if (resolveEmailToUserNameStatus.getStatusCode() != HttpStatus.OK) {
                return createStatusJSON("Successfully sent email", HttpStatus.OK);
            }
            String name = resolveEmailToUserNameStatus.getBody();

            // Gets the password reset code for the user
            ResponseEntity<String> resolveEmailToPasswordResetCodeStatus = dbconn.transaction_resolveEmailToPasswordResetCode(email);
            if (resolveEmailToPasswordResetCodeStatus.getStatusCode() != HttpStatus.OK) {
                return createStatusJSON("Failed to send email", HttpStatus.INTERNAL_SERVER_ERROR);
            }
            String passwordResetCode = resolveEmailToPasswordResetCodeStatus.getBody();

            // On success, send verification email
            return mailService.sendPasswordResetEmail(name, email, passwordResetCode);

        } finally {
            DatabaseConnectionPool.releaseConnection(dbconn);
        }
    }

    /**
     * Generates and serves a password reset page
     *
     * @apiNote GET request
     * @return HTML page. 200 status code iff success
     */
    @RequestMapping(path = "/reset",
        method = RequestMethod.GET)
    public ResponseEntity<String> servePasswordResetPage(@RequestParam(value = "code") String passwordResetCode) {

        DatabaseConnection dbconn = DatabaseConnectionPool.getConnection();

		try {
            ResponseEntity<Boolean> checkPasswordResetCodeValidStatus = dbconn.transaction_checkPasswordResetCodeValid(passwordResetCode);
            if (checkPasswordResetCodeValidStatus.getStatusCode() != HttpStatus.OK) {
                return new ResponseEntity<>( "Invalid password reset code", HttpStatus.NOT_FOUND);
            } else {
                return new ResponseEntity<>(loadTemplate("password_reset_page.html")
                                            .replace("[[url]]", API_HOST + "/auth/process-reset")
                                            .replace("[[passwordResetCode]]", passwordResetCode),
                        HttpStatus.OK);
            }
        } finally {
            DatabaseConnectionPool.releaseConnection(dbconn);
        }
    }

    /**
     * Resets the user's password
     *
     * @apiNote POST request
     * @return HTML page. 200 status code iff success
     */
    @RequestMapping(path = "/process-reset",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.TEXT_HTML_VALUE,
        method = RequestMethod.POST)
    public ResponseEntity<String> processResetCode(@RequestParam(value = "code") String passwordResetCode,
                                                   @RequestParam(value = "password") String newPassword) {

        System.out.println("processResetCode");
        DatabaseConnection dbconn = DatabaseConnectionPool.getConnection();

		try {
            System.out.println("A");
            ResponseEntity<Boolean> processPasswordResetCodeStatus = dbconn.transaction_processPasswordResetCode(passwordResetCode, newPassword);
            System.out.println(processPasswordResetCodeStatus);
            System.out.println("B");

            if (processPasswordResetCodeStatus.getStatusCode() == HttpStatus.OK) {
                System.out.println("C1");
                return new ResponseEntity<>(
                        loadTemplate("verification_success_page.html").replace("[[year]]",
                                               String.valueOf(Calendar.getInstance().get(Calendar.YEAR))),
                        HttpStatus.OK);
            } else {
                System.out.println("C2");
                return new ResponseEntity<>(
                        loadTemplate("verification_failed_page.html").replace("[[year]]",
                                               String.valueOf(Calendar.getInstance().get(Calendar.YEAR))),
                        processPasswordResetCodeStatus.getStatusCode());
            }
        } finally {
            System.out.println("D");
            DatabaseConnectionPool.releaseConnection(dbconn);
            System.out.println("E");
        }
    }
}
