package catering.user;

import javax.annotation.Nullable;
import javax.validation.constraints.NotEmpty;

public class RegistrationForm {
	@NotEmpty(message = "First name may not be empty")
	private final String firstName;
	@NotEmpty(message = "Last name may not be empty")
	private final String lastName;
	@NotEmpty(message = "Email may not be empty")
	private final String email;
	@NotEmpty(message = "Password may not be empty")
	private final String password;
	private final Position position;

	public RegistrationForm(String firstName,String lastName, String email, String password, Position position) {
		this.firstName = firstName;
		this.email = email;
		this.password = password;
		this.position = position;
		this.lastName = lastName;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public String getEmail() {
		return email;
	}

	public String getPassword() {
		return password;
	}

	public Position getPosition() {
		return position;
	}
	// gibt die Vor- und Nachname konkatiniert. dies wird als ID fürs Konto benutzt
	public String getUsername(){
		return firstName + " " + lastName;
	}

	@Override
	public String toString() {
		return "RegistrationForm{" +
				"firstName='" + firstName + '\'' +
				"lastName='" + lastName + '\'' +
				", email='" + email + '\'' +
				", password='" + password + '\'' +
				", position=" + position +
				'}';
	}
}
