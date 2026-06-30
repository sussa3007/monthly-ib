package com.monthlyib.server.domain.user.service;

import com.monthlyib.server.api.user.dto.UserPatchRequestDto;
import com.monthlyib.server.api.user.dto.UserResponseDto;
import com.monthlyib.server.api.user.dto.UserSocialReconcileResponseDto;
import com.monthlyib.server.api.user.dto.UserSocialPatchRequestDto;
import com.monthlyib.server.auth.dto.OauthInfo;
import com.monthlyib.server.auth.service.GoogleAuthService;
import com.monthlyib.server.auth.jwt.JwtTokenizer;
import com.monthlyib.server.auth.service.NaverAuthService;
import com.monthlyib.server.auth.service.RefreshService;
import com.monthlyib.server.auth.service.VerifyNumService;
import com.monthlyib.server.auth.token.Token;
import com.monthlyib.server.constant.*;
import com.monthlyib.server.domain.aidescriptive.entity.AiDescriptiveAnswer;
import com.monthlyib.server.domain.aidescriptive.repository.AiDescriptiveAnswerJpaRepository;
import com.monthlyib.server.domain.aihistory.entity.AiToolHistory;
import com.monthlyib.server.domain.aihistory.repository.AiToolHistoryJpaRepository;
import com.monthlyib.server.domain.aiia.entity.AiIARecommendation;
import com.monthlyib.server.domain.aiia.repository.AiIARecommendationJpaRepository;
import com.monthlyib.server.domain.aiio.entity.QuizSession;
import com.monthlyib.server.domain.aiio.entity.VoiceFeedback;
import com.monthlyib.server.domain.aiio.repository.QuizSessionJpaRepository;
import com.monthlyib.server.domain.aiio.repository.VoiceFeedbackJpaRepository;
import com.monthlyib.server.domain.accessanalytics.service.UserAccessAnalyticsService;
import com.monthlyib.server.domain.user.entity.User;
import com.monthlyib.server.domain.user.entity.UserImage;
import com.monthlyib.server.domain.user.entity.UserLoginProvider;
import com.monthlyib.server.domain.user.repository.UserRepository;
import com.monthlyib.server.domain.user.repository.UserLoginProviderJpaRepository;
import com.monthlyib.server.exception.ServiceLogicException;
import com.monthlyib.server.file.service.FileService;
import com.monthlyib.server.mail.service.EmailSender;
import com.monthlyib.server.openapi.user.dto.*;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class UserService {

    private final PasswordEncoder passwordEncoder;

    private final UserRepository userRepository;

    private final UserLoginProviderJpaRepository userLoginProviderJpaRepository;

    private final AiToolHistoryJpaRepository aiToolHistoryJpaRepository;

    private final AiIARecommendationJpaRepository aiIARecommendationJpaRepository;

    private final QuizSessionJpaRepository quizSessionJpaRepository;

    private final AiDescriptiveAnswerJpaRepository aiDescriptiveAnswerJpaRepository;

    private final VoiceFeedbackJpaRepository voiceFeedbackJpaRepository;

    private final JwtTokenizer tokenizer;

    private final RefreshService refreshService;

    private final HttpClientService httpClientService;

    private final VerifyNumService verifyNumService;

    private final NaverAuthService naverAuthService;

    private final GoogleAuthService googleAuthService;

    private final FileService fileService;

    private final EmailSender emailSender;

    private final UserAccessAnalyticsService userAccessAnalyticsService;

    @Value("${mail.subject.user.verification}")
    private String verificationSubject;

    @Value("${mail.subject.user.password-reset}")
    private String passwordResetSubject;

    @Value("${mail.template.name.user.join}")
    private String verificationTemplateName;

    @Value("${mail.template.name.user.password-reset}")
    private String passwordResetTemplateName;

    @Transactional(readOnly = true)
    public Page<UserResponseDto> findAll(int page, User user) {
        verifyAdmin(user);

        Page<User> users = userRepository.findAll(PageRequest.of(page, 15, Sort.by("createAt").descending()));
        return toUserResponseDtoPage(users);
    }


    public LoginApiResponseDto userLogin(LoginDto loginDto) {
        String username = loginDto.getUsername();
        String password = loginDto.getPassword();
        User findUser = findUserByUsername(username);
        verifyUserAvailable(findUser);
        if (password.equals(findUser.getPassword()) || passwordEncoder.matches(password, findUser.getPassword())) {
            return issueLoginResponse(findUser);
        } else {
            throw new ServiceLogicException(ErrorCode.WRONG_PASSWORD);
        }
    }

    public LoginApiResponseDto loginSocial(
            SocialLoginDto socialLoginDto,
            HttpServletResponse response
    ) {
        if (LoginType.GOOGLE.name().equalsIgnoreCase(socialLoginDto.getLoginType())) {
            throw new ServiceLogicException(
                    ErrorCode.BAD_REQUEST,
                    "Google 로그인은 전용 인증 플로우를 사용해야 합니다."
            );
        }

        String email = httpClientService.generateLoginRequest(socialLoginDto);
        LoginType loginType = LoginType.valueOf(socialLoginDto.getLoginType().toUpperCase());
        User user = findOrCreateSocialUser(email, loginType, null);
        LoginApiResponseDto loginResponse = issueLoginResponse(user);
        response.setHeader("userId", String.valueOf(user.getUserId()));
        response.setHeader("userStatus", user.getUserStatus().name());
        return loginResponse;
    }

    public LoginApiResponseDto loginSocialNaver(NaverLoginRequest naverLoginRequest, HttpServletResponse response) {
        OauthInfo info = naverAuthService.getNaverInfo(naverLoginRequest);
        User user = findOrCreateSocialUser(info.getEmail(), info.getLoginType(), info.getNickname());
        LoginApiResponseDto loginResponse = issueLoginResponse(user);
        response.setHeader("userId", String.valueOf(user.getUserId()));
        response.setHeader("userStatus", user.getUserStatus().name());
        return loginResponse;
    }

    public LoginApiResponseDto loginSocialGoogle(GoogleLoginRequest googleLoginRequest, HttpServletResponse response) {
        OauthInfo info = googleAuthService.getGoogleInfo(googleLoginRequest);
        User user = findOrCreateSocialUser(info.getEmail(), info.getLoginType(), info.getNickname());
        LoginApiResponseDto loginResponse = issueLoginResponse(user);
        response.setHeader("userId", String.valueOf(user.getUserId()));
        response.setHeader("userStatus", user.getUserStatus().name());
        return loginResponse;
    }

    @Transactional(readOnly = true)
    public UserResponseDto findUserById(Long userId, User user) {
        verifyAdminOrSelf(user, userId);

        return toUserResponseDto(findUserEntity(userId));
    }

    public UserResponseDto updateUser(Long userId, UserPatchRequestDto dto, User user) {
        verifyAdminOrSelf(user, userId);
        if (!user.getAuthority().equals(Authority.ADMIN)) {
            dto.setAuthority(null);
            dto.setUserStatus(null);
        }
        User findUser = findUserEntity(userId);
        String password = dto.getPassword();
        if (password != null) {
            String encodePassword = passwordEncoder.encode(password);
            dto.setPassword(encodePassword);
        }
        User updateUser = findUser.updateUser(dto);
        return toUserResponseDto(userRepository.save(updateUser));
    }


    public UserResponseDto updateSocialUser(Long userId, UserSocialPatchRequestDto dto, User requestUser) {
        verifyAdminOrSelf(requestUser, userId);
        User findUser = findUserEntity(userId);
        verifySocialPatchRequest(dto);
        if (!findUser.getUsername().equals(dto.getUsername())) {
            verifyUsername(dto.getUsername());
        }
        User updateUser = findUser.updateSocialUser(dto);
        updateUser.setUserStatus(UserStatus.ACTIVE);
        return toUserResponseDto(userRepository.save(updateUser));
    }

    public UserResponseDto createUser(UserPostRequestDto dto) {
        String normalizedEmail = normalizeEmail(dto.getEmail());
        dto.setEmail(normalizedEmail);
        Optional<User> user = verifyEmail(normalizedEmail);
        if (user.isPresent()) throw new ServiceLogicException(ErrorCode.USER_EXIST);
        verifyNum(VerifyNumRequestDto.builder().email(normalizedEmail).verifyNum(dto.getVerifyNum()).build());

        String password = dto.getPassword();
        String pwd = passwordEncoder.encode(password);
        dto.setPassword(pwd);
        User newUser = User.createUser(dto);
        User saveUser = userRepository.save(newUser);
        verifyNumService.deleteNum(normalizedEmail);
        return toUserResponseDto(saveUser);
    }

    public void verifyEmailNumPost(EmailRequestDto dto) {
        sendVerificationEmail(normalizeEmail(dto.getEmail()));
    }


    public void verifyPwdEmail(EmailRequestDto dto) {
        sendVerificationEmail(normalizeEmail(dto.getEmail()));
    }

    public void verifyNum(VerifyNumRequestDto dto) {
        String verifyNum = dto.getVerifyNum() == null ? "" : dto.getVerifyNum().trim();
        String email = normalizeEmail(dto.getEmail());

        if (email.isBlank()) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "이메일을 입력해주세요.");
        }
        if (verifyNum.isBlank()) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "인증번호를 입력해주세요.");
        }

        VerifyNumDto num = verifyNumService.getNum(email);
        if (num.getCreatedAt() != null && num.getCreatedAt().plusMinutes(30).isBefore(LocalDateTime.now())) {
            verifyNumService.deleteNum(email);
            throw new ServiceLogicException(ErrorCode.EXPIRED_VERIFY);
        }
        if (!verifyNum.equals(num.getVerifyNum())) {
            throw new ServiceLogicException(ErrorCode.WRONG_VERIFY_NUM);
        }
    }

    public void resetPassword(PasswordResetRequestDto dto) {
        String username = dto.getUsername() == null ? "" : dto.getUsername().trim();
        String email = normalizeEmail(dto.getEmail());

        if (username.isBlank()) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "아이디를 입력해주세요.");
        }
        if (email.isBlank()) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "이메일을 입력해주세요.");
        }

        verifyNum(VerifyNumRequestDto.builder()
                .email(email)
                .verifyNum(dto.getVerifyNum())
                .build());

        User user = findUserByUsername(username);
        verifyUserStatus(user.getUserStatus());

        if (!user.getEmail().equalsIgnoreCase(email)) {
            throw new ServiceLogicException(ErrorCode.NOT_FOUND_USER, "입력한 아이디와 이메일이 일치하는 회원을 찾을 수 없습니다.");
        }

        if (!LoginType.BASIC.equals(user.getLoginType())) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "소셜 로그인 계정은 해당 소셜 로그인으로 이용해주세요.");
        }

        String temporaryPassword = generateTemporaryPassword(10);
        String[] to = new String[]{email};
        String message = user.getNickName() + "님, 임시 비밀번호는 " + temporaryPassword + " 입니다. 로그인 후 반드시 비밀번호를 변경해주세요.";

        try {
            emailSender.sendEmail(
                    to,
                    passwordResetSubject,
                    message,
                    passwordResetTemplateName,
                    java.util.Map.of("recipientName", user.getNickName())
            );
        } catch (MailSendException e) {
            log.error("Failed to send password reset email to {}", email, e);
            throw new ServiceLogicException(ErrorCode.MAIL_SEND_FAILED, "임시 비밀번호 메일 발송에 실패했습니다. 다시 시도해주세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Password reset email sending interrupted for {}", email, e);
            throw new ServiceLogicException(ErrorCode.MAIL_SEND_FAILED, "임시 비밀번호 메일 발송에 실패했습니다. 다시 시도해주세요.");
        }

        user.setPassword(passwordEncoder.encode(temporaryPassword));
        userRepository.save(user);
        verifyNumService.deleteNum(email);
    }

    public UserSocialReconcileResponseDto reconcileSocialLinks(User requestUser) {
        verifyAdmin(requestUser);

        List<String> duplicateEmails = userRepository.findDuplicateNormalizedEmails();
        List<Long> mergedSourceUserIds = new ArrayList<>();
        List<Long> canonicalUserIds = new ArrayList<>();
        int movedAiHistoryCount = 0;
        int movedIaRecommendationCount = 0;
        int movedQuizSessionCount = 0;
        int movedDescriptiveAnswerCount = 0;
        int movedVoiceFeedbackCount = 0;

        for (String email : duplicateEmails) {
            List<User> users = userRepository.findAllByEmail(email);
            if (users.size() < 2) {
                continue;
            }

            User canonicalUser = selectCanonicalUser(users);
            canonicalUserIds.add(canonicalUser.getUserId());

            for (User duplicateUser : users) {
                if (duplicateUser.getUserId().equals(canonicalUser.getUserId())) {
                    continue;
                }

                movedAiHistoryCount += moveAiToolHistories(duplicateUser, canonicalUser);
                movedIaRecommendationCount += moveIaRecommendations(duplicateUser, canonicalUser);
                movedQuizSessionCount += moveQuizSessions(duplicateUser, canonicalUser);
                movedDescriptiveAnswerCount += moveDescriptiveAnswers(duplicateUser, canonicalUser);
                movedVoiceFeedbackCount += moveVoiceFeedbacks(duplicateUser, canonicalUser);
                moveProviderLinks(duplicateUser, canonicalUser);

                duplicateUser.setMergedIntoUserId(canonicalUser.getUserId());
                duplicateUser.setUserStatus(UserStatus.INACTIVE);
                userRepository.save(duplicateUser);
                mergedSourceUserIds.add(duplicateUser.getUserId());
            }
        }

        return UserSocialReconcileResponseDto.builder()
                .mergedUserCount(mergedSourceUserIds.size())
                .movedAiHistoryCount(movedAiHistoryCount)
                .movedIaRecommendationCount(movedIaRecommendationCount)
                .movedQuizSessionCount(movedQuizSessionCount)
                .movedDescriptiveAnswerCount(movedDescriptiveAnswerCount)
                .movedVoiceFeedbackCount(movedVoiceFeedbackCount)
                .mergedSourceUserIds(mergedSourceUserIds)
                .canonicalUserIds(canonicalUserIds.stream().distinct().toList())
                .build();
    }

    public void verifyUsername(UsernameVerifyDto dto) {
        String username = dto.getUsername();
        verifyUsername(username);
    }


    public void deleteUser(Long userId, User user) {
        verifyAdmin(user);
        User findUser = findUserEntity(userId);
        findUser.setUserStatus(UserStatus.INACTIVE);
        userRepository.save(findUser);

    }

    public void deleteUser(Long userId) {
        User findUser = findUserEntity(userId);
        findUser.setUserStatus(UserStatus.INACTIVE);
        userRepository.save(findUser);
    }


    private User findOrCreateSocialUser(String email, LoginType loginType, String suggestedNickname) {
        String normalizedEmail = normalizeEmail(email);
        User linkedUser = userLoginProviderJpaRepository
                .findByProviderAndProviderEmail(loginType, normalizedEmail)
                .map(UserLoginProvider::getUser)
                .orElse(null);

        if (linkedUser != null) {
            verifyUserAvailable(linkedUser);
            return linkedUser;
        }

        Optional<User> existingUser = userRepository.findByEmail(normalizedEmail);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            verifyUserAvailable(user);
            linkProviderIfAbsent(user, loginType, normalizedEmail);
            return user;
        }

        String username = generateAvailableUsername(normalizedEmail);
        String nickname = buildSocialNickname(normalizedEmail, suggestedNickname);
        User newUser = User.createEmptyUser(normalizedEmail, loginType.name(), username, nickname);
        User savedUser = userRepository.save(newUser);
        linkProviderIfAbsent(savedUser, loginType, normalizedEmail);
        return savedUser;
    }

    private void sendVerificationEmail(String email) {
        String resolvedEmail = email == null ? "" : email.trim();
        if (resolvedEmail.isBlank()) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "이메일을 입력해주세요.");
        }

        String verificationNum = generateVerificationNumber(6);
        String[] to = new String[]{resolvedEmail};
        String message = resolvedEmail + "님, 인증번호는 " + verificationNum + " 입니다.";

        verifyNumService.createNum(resolvedEmail, verificationNum);

        try {
            emailSender.sendEmail(to, verificationSubject, message, verificationTemplateName);
        } catch (MailSendException e) {
            verifyNumService.deleteNum(resolvedEmail);
            log.error("Failed to send verification email to {}", resolvedEmail, e);
            throw new ServiceLogicException(ErrorCode.VERIFY_EMAIL_SEND_FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            verifyNumService.deleteNum(resolvedEmail);
            log.error("Verification email sending interrupted for {}", resolvedEmail, e);
            throw new ServiceLogicException(ErrorCode.VERIFY_EMAIL_SEND_FAILED);
        }
    }

    private String generateVerificationNumber(int len) {
        Random rand = new Random();
        StringBuilder numStr = new StringBuilder();

        while (numStr.length() < len) {
            String ran = Integer.toString(rand.nextInt(10));
            if (numStr.indexOf(ran) < 0) {
                numStr.append(ran);
            }
        }

        return numStr.toString();
    }

    private String generateTemporaryPassword(int len) {
        final String candidates = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        Random rand = new Random();
        StringBuilder password = new StringBuilder();

        while (password.length() < len) {
            password.append(candidates.charAt(rand.nextInt(candidates.length())));
        }

        return password.toString();
    }

    private void verifyAdmin(User user) {
        if (!user.getAuthority().equals(Authority.ADMIN)) {
            throw new ServiceLogicException(ErrorCode.ACCESS_DENIED);
        }
    }

    private void verifyAdminOrSelf(User user, Long userId) {
        if (!user.getAuthority().equals(Authority.ADMIN) && !user.getUserId().equals(userId)) {
            throw new ServiceLogicException(ErrorCode.ACCESS_DENIED);
        }
    }

    private void verifySocialPatchRequest(UserSocialPatchRequestDto dto) {
        if (dto == null
                || isBlank(dto.getUsername())
                || isBlank(dto.getNickName())
                || isBlank(dto.getBirth())
                || isBlank(dto.getSchool())
                || isBlank(dto.getGrade())
                || isBlank(dto.getAddress())
                || isBlank(dto.getCountry())) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "필수 회원 정보가 누락되었습니다.");
        }

        if (!dto.isTermsOfUseCheck() || !dto.isPrivacyTermsCheck()) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST, "필수 약관 동의가 필요합니다.");
        }
    }


    public LoginApiResponseDto refreshToken(Long userId, RefreshTokenRequestDto requestDto) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ServiceLogicException(ErrorCode.NOT_FOUND_USER));
            verifyUserAvailable(user);
            String requestRefreshToken = Optional.ofNullable(requestDto)
                    .map(RefreshTokenRequestDto::getRefreshToken)
                    .filter(token -> !token.isBlank())
                    .orElseThrow(() -> new ServiceLogicException(ErrorCode.EXPIRED_REFRESH_TOKEN));
            RefreshDto refresh = refreshService.getRefresh(user.getUsername());
            tokenizer.verifyAccessToken(requestRefreshToken);
            if (!refresh.getRefreshToken().equals(requestRefreshToken)) {
                throw new ServiceLogicException(ErrorCode.EXPIRED_REFRESH_TOKEN);
            }
            String refreshUsername = tokenizer.getUsername(requestRefreshToken);
            if (!user.getUsername().equals(refreshUsername)) {
                throw new ServiceLogicException(ErrorCode.EXPIRED_REFRESH_TOKEN);
            }
            long refreshSessionVersion = Long.parseLong(String.valueOf(
                    tokenizer.getClaims(
                            requestRefreshToken,
                            tokenizer.encodeBase64SecretKey(tokenizer.getSecretKey())
                    ).getBody().getOrDefault("sessionVersion", 0L)
            ));
            long currentSessionVersion = user.getSessionVersion() == null ? 0L : user.getSessionVersion();
            if (refreshSessionVersion != currentSessionVersion) {
                throw new ServiceLogicException(ErrorCode.SESSION_EXPIRED_BY_NEW_LOGIN);
            }
            LocalDateTime accessAt = LocalDateTime.now();
            user.touchLastAccessAt(accessAt);
            userRepository.save(user);
            userAccessAnalyticsService.recordAccess(user, accessAt);
            Token token = tokenizer.delegateToken(user);
            return LoginApiResponseDto.of(token, user, resolveLinkedProviders(user));
        } catch (ServiceLogicException e) {
            throw e;
        } catch (ExpiredJwtException e) {
            throw new ServiceLogicException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        } catch (Exception ex) {
            throw new ServiceLogicException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public UserResponseDto createOrUpdateUserImage(Long userId, MultipartFile[] files) {
        User findUser = findUserEntity(userId);
        List<UserImage> currentList = userRepository.findAllUserImage(userId);
        if (!currentList.isEmpty()) {
            currentList.forEach( m ->
                    fileService.deleteAwsFile(m.getFileName(), AwsProperty.USER_IMAGE)
            );
        }
        userRepository.deleteAllUserImage(userId);
        UserImage image = null;
        for (MultipartFile file : files) {
            String url = fileService.saveMultipartFileForAws(file, AwsProperty.USER_IMAGE);
            String filename = file.getOriginalFilename();
            UserImage userImage = UserImage.create(filename, url, userId);
            image = userRepository.saveUserImage(userImage);
        }
        return UserResponseDto.of(findUser, image, resolveLinkedProviders(findUser));
    }

    public User findUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ServiceLogicException(ErrorCode.NOT_FOUND_USER));
    }

    public void verifyUsername(String username) {
        Optional<User> byUsername = userRepository.findByUsername(username);
        if (byUsername.isPresent()) {
            throw new ServiceLogicException(ErrorCode.USER_EXIST);
        }
    }

    public Optional<User> verifyEmail(String email) {
        return userRepository.findByEmail(normalizeEmail(email));
    }

    public User findUserEntity(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ServiceLogicException(ErrorCode.NOT_FOUND_USER));
    }

    public void verifyUserStatus(UserStatus userStatus) {
        if (userStatus.equals(UserStatus.INACTIVE)) {
            throw new ServiceLogicException(ErrorCode.BLOCK_OR_INACTIVE_USER);
        }
    }

    private void verifyUserAvailable(User user) {
        if (user.getMergedIntoUserId() != null) {
            throw new ServiceLogicException(ErrorCode.BLOCK_OR_INACTIVE_USER);
        }
        verifyUserStatus(user.getUserStatus());
    }

    private UserImage firstUserImage(Long userId) {
        List<UserImage> image = userRepository.findAllUserImage(userId);
        if (image.isEmpty()) {
            return null;
        } else {
            return image.get(0);
        }
    }

    private LoginApiResponseDto issueLoginResponse(User user) {
        User persistedUser = rotateUserSession(user);
        Token token = tokenizer.delegateToken(persistedUser);
        return LoginApiResponseDto.of(token, persistedUser, resolveLinkedProviders(persistedUser));
    }

    private User rotateUserSession(User user) {
        long currentSessionVersion = user.getSessionVersion() == null ? 0L : user.getSessionVersion();
        user.setSessionVersion(currentSessionVersion + 1L);
        LocalDateTime accessAt = LocalDateTime.now();
        user.touchLastAccessAt(accessAt);
        User saved = userRepository.save(user);
        userAccessAnalyticsService.recordAccess(saved, accessAt);
        return saved;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private UserResponseDto toUserResponseDto(User user) {
        return UserResponseDto.of(user, firstUserImage(user.getUserId()), resolveLinkedProviders(user));
    }

    private Page<UserResponseDto> toUserResponseDtoPage(Page<User> users) {
        List<Long> userIds = users.getContent().stream()
                .map(User::getUserId)
                .toList();
        Map<Long, UserImage> imageMap = firstUserImageMap(userIds);
        Map<Long, List<UserLoginProvider>> providerMap = linkedProviderMap(userIds);

        return users.map(user -> UserResponseDto.of(
                user,
                imageMap.get(user.getUserId()),
                resolveLinkedProviders(user, providerMap.getOrDefault(user.getUserId(), List.of()))
        ));
    }

    private Map<Long, UserImage> firstUserImageMap(List<Long> userIds) {
        Map<Long, UserImage> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }

        userRepository.findAllUserImageByUserIds(userIds).forEach(image ->
                result.putIfAbsent(image.getUserId(), image)
        );
        return result;
    }

    private Map<Long, List<UserLoginProvider>> linkedProviderMap(List<Long> userIds) {
        Map<Long, List<UserLoginProvider>> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }

        userLoginProviderJpaRepository.findAllByUserUserIdIn(userIds).forEach(provider -> {
            Long userId = provider.getUser().getUserId();
            result.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(provider);
        });
        return result;
    }

    private List<LoginType> resolveLinkedProviders(User user) {
        return resolveLinkedProviders(user, userLoginProviderJpaRepository.findAllByUserUserId(user.getUserId()));
    }

    private List<LoginType> resolveLinkedProviders(User user, List<UserLoginProvider> linkedProviders) {
        Set<LoginType> providerSet = new LinkedHashSet<>();
        providerSet.add(user.getLoginType());
        linkedProviders.stream()
                .map(UserLoginProvider::getProvider)
                .forEach(providerSet::add);
        return new ArrayList<>(providerSet);
    }

    private void linkProviderIfAbsent(User user, LoginType provider, String providerEmail) {
        String normalizedEmail = normalizeEmail(providerEmail);
        boolean exists = userLoginProviderJpaRepository
                .findByProviderAndProviderEmail(provider, normalizedEmail)
                .isPresent();
        if (exists) {
            return;
        }
        userLoginProviderJpaRepository.save(UserLoginProvider.create(user, provider, normalizedEmail));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String buildSocialNickname(String normalizedEmail, String suggestedNickname) {
        if (!isBlank(suggestedNickname)) {
            return suggestedNickname.trim();
        }
        return localPart(normalizedEmail);
    }

    private String generateAvailableUsername(String email) {
        String base = slugifyUsername(localPart(email));
        if (base.isBlank()) {
            base = "user";
        }
        String candidate = base;
        int suffix = 1;
        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String localPart(String email) {
        String normalizedEmail = normalizeEmail(email);
        int atIndex = normalizedEmail.indexOf("@");
        if (atIndex <= 0) {
            return normalizedEmail;
        }
        return normalizedEmail.substring(0, atIndex);
    }

    private String slugifyUsername(String source) {
        String normalized = Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFKC)
                .toLowerCase()
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        return normalized;
    }

    private User selectCanonicalUser(List<User> users) {
        return users.stream()
                .sorted(Comparator
                        .comparingInt((User user) -> userStatusRank(user.getUserStatus()))
                        .thenComparing(User::getCreateAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(User::getUserId))
                .findFirst()
                .orElseThrow(() -> new ServiceLogicException(ErrorCode.NOT_FOUND_USER));
    }

    private int userStatusRank(UserStatus status) {
        if (status == UserStatus.ACTIVE) {
            return 0;
        }
        if (status != UserStatus.WAIT_INFO && status != UserStatus.INACTIVE) {
            return 1;
        }
        if (status == UserStatus.WAIT_INFO) {
            return 2;
        }
        return 3;
    }

    private int moveAiToolHistories(User fromUser, User toUser) {
        List<AiToolHistory> histories = aiToolHistoryJpaRepository.findAllByUserUserId(fromUser.getUserId());
        histories.forEach(history -> history.setUser(toUser));
        aiToolHistoryJpaRepository.saveAll(histories);
        return histories.size();
    }

    private int moveIaRecommendations(User fromUser, User toUser) {
        List<AiIARecommendation> recommendations = aiIARecommendationJpaRepository.findAllByUserUserId(fromUser.getUserId());
        recommendations.forEach(recommendation -> recommendation.setUser(toUser));
        aiIARecommendationJpaRepository.saveAll(recommendations);
        return recommendations.size();
    }

    private int moveQuizSessions(User fromUser, User toUser) {
        List<QuizSession> sessions = quizSessionJpaRepository.findAllByUserUserId(fromUser.getUserId());
        sessions.forEach(session -> session.setUser(toUser));
        quizSessionJpaRepository.saveAll(sessions);
        return sessions.size();
    }

    private int moveDescriptiveAnswers(User fromUser, User toUser) {
        List<AiDescriptiveAnswer> answers = aiDescriptiveAnswerJpaRepository.findAllByUserUserId(fromUser.getUserId());
        answers.forEach(answer -> answer.setUser(toUser));
        aiDescriptiveAnswerJpaRepository.saveAll(answers);
        return answers.size();
    }

    private int moveVoiceFeedbacks(User fromUser, User toUser) {
        List<VoiceFeedback> feedbacks = voiceFeedbackJpaRepository.findAllByAuthorId(fromUser.getUserId());
        String canonicalAuthor = isBlank(toUser.getNickName()) ? toUser.getUsername() : toUser.getNickName();
        feedbacks.forEach(feedback -> {
            feedback.setAuthorId(toUser.getUserId());
            feedback.setAuthor(canonicalAuthor);
        });
        voiceFeedbackJpaRepository.saveAll(feedbacks);
        return feedbacks.size();
    }

    private void moveProviderLinks(User fromUser, User toUser) {
        List<UserLoginProvider> providers = userLoginProviderJpaRepository.findAllByUserUserId(fromUser.getUserId());
        for (UserLoginProvider provider : providers) {
            Optional<UserLoginProvider> existing = userLoginProviderJpaRepository.findByProviderAndProviderEmail(
                    provider.getProvider(),
                    provider.getProviderEmail()
            );
            if (existing.isPresent() && existing.get().getUser().getUserId().equals(toUser.getUserId())) {
                userLoginProviderJpaRepository.delete(provider);
                continue;
            }
            provider.setUser(toUser);
            userLoginProviderJpaRepository.save(provider);
        }
    }


}
