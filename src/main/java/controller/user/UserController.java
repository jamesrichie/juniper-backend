package controller.user;

import java.io.*;
import java.sql.*;
import java.util.*;

import org.apache.commons.text.WordUtils;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import model.*;
import services.*;
import exceptions.*;

@RestController
@RequestMapping("/user")
public class UserController {

    // Database connection
    private final DatabaseConnection dbconn;

    // Token authentication service
    private final AuthTokenService authTokenService;

    // Mailing service
    private final MailService mailService;

    public UserController() throws IOException, SQLException {
        dbconn = new DatabaseConnection();
        authTokenService = new AuthTokenService();
        mailService = new MailService();
    }

    /**
     * Creates a new user account
     *
     * @return HTTP status code with message
     * @url .../user/createUser?name=value1&email=value2&password=value3
     */
    @PostMapping("/createUser")
    public ResponseEntity<String> createUser(@RequestParam(value = "name") String name,
                                             @RequestParam(value = "email") String email,
                                             @RequestParam(value = "password") String password) {

        name = WordUtils.capitalizeFully(name);
        email = email.toLowerCase();

        String userId = UUID.randomUUID().toString();
        String userHandle = name.replaceAll("\\s", "").toLowerCase() + "#" + String.format("%04d", new Random().nextInt(10000));

        // check that user handle is unique
        while (dbconn.transaction_userHandleToUserIdResolution(userHandle).getBody() != null) {
            userHandle = name.replaceAll("\\s", "").toLowerCase() + "#" + String.format("%04d", new Random().nextInt(10000));
        }

        String verificationCode = generateVerificationCode(64);

        // creates the user
        ResponseEntity<Boolean> createUserStatus = dbconn.transaction_createUser(userId, userHandle, name, email, password, verificationCode);
        if (createUserStatus.getStatusCode() != HttpStatus.OK) {
            return new ResponseEntity<>("Failed to create user\n", createUserStatus.getStatusCode());
        }
        // on success, send verification email
        return mailService.sendVerificationEmail(name, email, verificationCode);
    }

    /**
     * Sends a verification email
     *
     * @return HTTP status code with message
     * @url .../user/sendVerificationEmail?email=value1
     */
    @PostMapping("/sendVerificationEmail")
    public ResponseEntity<String> sendVerificationEmail(@RequestParam(value = "email") String email) {

        email = email.toLowerCase();

        // gets the name of the user
        ResponseEntity<String> getUserNameStatus = dbconn.transaction_getUserName(email);
        if (getUserNameStatus.getStatusCode() != HttpStatus.OK) {
            return getUserNameStatus;
        }
        String name = getUserNameStatus.getBody();

        // gets the verification code for the user
        ResponseEntity<String> getVerificationCodeStatus = dbconn.transaction_getVerificationCode(email);
        if (getVerificationCodeStatus.getStatusCode() != HttpStatus.OK) {
            return getVerificationCodeStatus;
        }
        String verificationCode = getVerificationCodeStatus.getBody();

        // on success, send verification email
        return mailService.sendVerificationEmail(name, email, verificationCode);
    }

    /**
     * Verifies a user and redirects them to the login page
     *
     * @return HTTP status code with message
     * @url .../user/verify?code=value1
     */
    @GetMapping("/verifyEmail")
    public ResponseEntity<String> verifyEmail(@RequestParam(value = "code") String verificationCode) {

        ResponseEntity<Boolean> verifyEmailStatus = dbconn.transaction_verifyEmail(verificationCode);

        return switch (verifyEmailStatus.getStatusCode()) {
            case OK -> new ResponseEntity<>("Successfully verified user\n", HttpStatus.OK);
            case BAD_REQUEST -> new ResponseEntity<>("User already verified\n", HttpStatus.BAD_REQUEST);
            case GONE -> new ResponseEntity<>("Verification code has expired\n", HttpStatus.GONE);
            default -> new ResponseEntity<>("Failed to verify user\n", verifyEmailStatus.getStatusCode());
        };
    }

    /**
     * Records changes to the user's profile
     *
     * @return true iff operation succeeds
     * @effect HTTP POST Request updates the model/database
     */
    @PostMapping(value = "/updateUserProfile", consumes = "application/json", produces = "application/json")
    public String updateUserProfile(@RequestBody String user, @RequestParam(value = "access_token") String accessToken) {

        // Overview
        // ––––––––––––––––––––––––––––––––––––––––––––––––––––––
        // assert that userId and password matches those stored
        // in the database before updating the associated profile
        // ––––––––––––––––––––––––––––––––––––––––––––––––––––––

        throw new NotYetImplementedException();
    }

    /**
     * Generates a random string of specified length
     */
    private String generateVerificationCode(int length) {
        int leftLimit = 97;
        int rightLimit = 122;

        Random random = new Random();
        StringBuilder buffer = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            int randomLimitedInt = leftLimit + (int) (random.nextFloat() * (rightLimit - leftLimit + 1));
            buffer.append((char) randomLimitedInt);
        }
        return buffer.toString();
    }
}
