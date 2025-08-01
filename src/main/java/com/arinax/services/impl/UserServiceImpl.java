package com.arinax.services.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.arinax.config.AppConstants;
import com.arinax.entities.Role;
import com.arinax.entities.User;
import com.arinax.entities.UserTransaction;
import com.arinax.exceptions.ApiException;
import com.arinax.exceptions.ResourceNotFoundException;
import com.arinax.playloads.ApiResponse;
import com.arinax.playloads.UserDto;
import com.arinax.playloads.VerificationDto;
import com.arinax.repositories.RoleRepo;
import com.arinax.repositories.UserRepo;
import com.arinax.repositories.UserTransactionRepo;
import com.arinax.services.NotificationService;
import com.arinax.services.UserService;


@Service
public class UserServiceImpl implements UserService {

	@Autowired
	UserTransactionRepo userTransactionRepo;
	@Autowired
	private UserRepo userRepo;

	@Autowired
	private ModelMapper modelMapper;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private RoleRepo roleRepo;
	
	@Autowired
	private VerificationService verificationService;

	@Autowired
	private NotificationService notificationService;

	private static final long OTP_VALID_DURATION = 5 * 60; // 5 min in seconds

	
	@Override
	   public ApiResponse verifyUser(String emailOrMobile, String otp) {
		    VerificationDto dto = verificationService.getOtpDetails(
		        emailOrMobile.contains("@") ? "email:" + emailOrMobile : "mobile:" + emailOrMobile
		    );

		    if (dto == null) {
		        return new ApiResponse("No OTP found for this user", false);
		    }

		    if (!dto.getOtp().equals(otp)) {
		        return new ApiResponse("Invalid OTP", false);
		    }

		    if (Duration.between(dto.getTimestamp(), Instant.now()).getSeconds() > OTP_VALID_DURATION) {
		        //verificationService.removeOtp(emailOrMobile);
		        return new ApiResponse("OTP expired", false);
		    }

		    // verified => remove OTP
		    //verificationService.removeOtp(emailOrMobile);
		    return new ApiResponse("User verified successfully", true);
		}   

	
	@Override
	public UserDto registerNewUser(UserDto userDto) {

		User user = this.modelMapper.map(userDto, User.class);

//		if(userDto.getMobileNo()==null) {
//			throw new ApiException("Mobile Number Also Required To SignIn");
//		}
		// OTP Verify
	    VerificationDto verification = verificationService.getOtpDetails(userDto.getEmail());

	    if (verification == null || !verification.getOtp().equals(userDto.getOtp())) {
	        throw new ApiException("Invalid OTP or email!");
	    }
	    
	    // Time validation
	    Instant otpGeneratedTime = verification.getTimestamp();
	    Instant now = Instant.now();

	    long elapsedSeconds = Duration.between(otpGeneratedTime, now).getSeconds();

	    if (elapsedSeconds > OTP_VALID_DURATION) {
	        verificationService.removeOtp(userDto.getEmail()); // OTP expire भैसकेपछि हटाउने
	        throw new ApiException("OTP expired! Please request a new one.");
	    }
	    
	    // OTP सही भयो र time भित्र verify भयो भने हटाउने
	    verificationService.removeOtp(userDto.getEmail());

		// encoded the password
		user.setPassword(this.passwordEncoder.encode(user.getPassword()));

		// roles
		Role role = this.roleRepo.findById(AppConstants.NORMAL_USER)
				.orElseThrow(() -> new RuntimeException("Role not found with id: " + AppConstants.NORMAL_USER));

		user.setRoles(new HashSet<>());          // initialize
		user.getRoles().add(role);               // one role add (NORMAL_USER)
		String generatedUsername = generateUniqueUsername(user.getEmail(), userDto.getMobileNo());
		
        user.setURemark(generatedUsername);
        if (userDto.getMobileNo()!=null) {
		user.setMobileNo(userDto.getMobileNo());
        }
		User newUser = this.userRepo.save(user);
		String welcomeMessage = String.format("Welcome, %s! We're excited to have you on our ArinaX. enjoy the journey ahead! "
        		+ "Thank you for choosing us, Arinax", user.getName());
       // sendmsg.sendMessage(user.getMobileNo(), welcomeMessage); // Assuming notificationService sends SMS

		
		 //notificationService.createNotification(newUser.getId(), welcomeMessage);
		return this.modelMapper.map(newUser, UserDto.class);
	}

	
	
	public String generateUniqueUsername(String email, String mobileNo) {
	    String emailPrefix = email.split("@")[0]; // e.g., "john.doe"
	    String mobileSuffix = mobileNo.substring(mobileNo.length() - 4); // e.g., "2334"9816032025
	    String baseUsername = emailPrefix.replaceAll("[^a-zA-Z0-9]", "").toLowerCase() + mobileSuffix;

	    String finalUsername = baseUsername;
	    int counter = 1;

	    while (userRepo.existsByuRemark(finalUsername)) {
	        finalUsername = baseUsername + counter;
	        counter++;
	    }

	    return finalUsername;
	}

	
	
	@Override
	public UserDto createUser(UserDto userDto) {
		User user = this.dtoToUser(userDto);
		User savedUser = this.userRepo.save(user);
		return this.userToDto(savedUser);
	}

	@Override
	public UserDto updateUser(UserDto userDto, Integer userId) {
	    User user = this.userRepo.findById(userId)
	            .orElseThrow(() -> new ResourceNotFoundException("User", "Id", userId));

	    user.setName(userDto.getName());
	    user.setEmail(userDto.getEmail());
	    user.setFuId(userDto.getFuId());
	    user.setPuId(userDto.getPuId());

	    // Encode password before update
	    if (userDto.getPassword() != null && !userDto.getPassword().isEmpty()) {
	        user.setPassword(this.passwordEncoder.encode(userDto.getPassword()));
	    }

	    User updatedUser = this.userRepo.save(user);
	    return this.userToDto(updatedUser);
	}

	
	@Override
	public UserDto BalanceUpdate(UserDto userDto, Integer userId) {
	    User user = userRepo.findById(userId)
	            .orElseThrow(() -> new ResourceNotFoundException("User", "Id", userId));

	    if (userDto.getBalance() == null) {
	        throw new ApiException("Balance cannot be null");
	    }

	    double incomingBalance = userDto.getBalance();

	    user.setBalance(user.getBalance() + incomingBalance);
	    
	    UserTransaction txn = new UserTransaction();
        txn.setUser(user);
        txn.setAmount(+incomingBalance); // Positive means credited
        txn.setType("CREDITED");
        txn.setReason("coin is added in Your account");
        txn.setDateTime(LocalDateTime.now());
        userTransactionRepo.save(txn); // Save transaction
        
	    return userToDto(userRepo.save(user));
	}

		
	

	@Override
	public UserDto getUserById(Integer userId) {

		User user = this.userRepo.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User", " Id ", userId));

		return this.userToDto(user);
	}

	@Override
	public List<UserDto> getAllUsers() {

		List<User> users = this.userRepo.findAll();
		List<UserDto> userDtos = users.stream().map(user -> this.userToDto(user)).collect(Collectors.toList());

		return userDtos;
	}

	@Override
	public void deleteUser(Integer userId) {
		User user = this.userRepo.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User", "Id", userId));
		this.userRepo.delete(user);

	}

	public User dtoToUser(UserDto userDto) {
		User user = this.modelMapper.map(userDto, User.class);
		return user;
	}

	public UserDto userToDto(User user) {
		UserDto userDto = this.modelMapper.map(user, UserDto.class);
		return userDto;
	}

	 @Override
	   public UserDto updateDeviceToken(UserDto userDto, Integer userId) {
		    User user = this.userRepo.findById(userId)
		            .orElseThrow(() -> new ResourceNotFoundException("User", "Id", userId));
		    user.setDeviceToken(userDto.getDeviceToken());  
		    // Save the updated user back to the repository
		    User updatedUser = this.userRepo.save(user);
		    // Convert the updated User to UserDto and return it
		    return this.userToDto(updatedUser);
		}

	
	 @Override
		public void addRoleToUser(String email, String roleName) {

		    // Fetch user by email
		    User user = userRepo.findByEmail(email)
		            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));

		    // Fetch role by name
		    Role role = roleRepo.findByName(roleName)
		            .orElseThrow(() -> new ResourceNotFoundException("Role", "name", roleName));

		    // Check if the user already has the role
		    boolean roleExists = user.getRoles().stream()
		        .anyMatch(existingRole -> existingRole.getName().equals(roleName));

		    if (roleExists) {
		        throw new ApiException("Role '" + roleName + "' is already assigned to the user.");
		    }
		    // Clear existing roles and assign new role
		    user.getRoles().add(role);
		    userRepo.save(user);
		    System.out.println("User role changed to " + roleName + ".");
		}



		
		@Override
	    public UserDto getUserByEmail(String email) {
	        User user = userRepo.findByEmail(email)
	                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
	        return modelMapper.map(user, UserDto.class);
	    }

		@Override
		public List<UserDto> getUsersByRole(String roleName) {
			Role role = roleRepo.findByName(roleName)
	                .orElseThrow(() -> new ResourceNotFoundException("Role", "name", roleName));
	        return userRepo.findAll().stream()
	                .filter(user -> user.getRoles().contains(role))
	                .map(user -> modelMapper.map(user, UserDto.class))
	                .collect(Collectors.toList());
	    
		}

	
}

