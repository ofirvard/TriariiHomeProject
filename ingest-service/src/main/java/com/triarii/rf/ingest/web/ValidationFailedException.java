package com.triarii.rf.ingest.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400 problem detail carrying every validation error, not just the first one. */
public class ValidationFailedException extends ErrorResponseException {

    public ValidationFailedException(List<String> errors) {
        super(HttpStatus.BAD_REQUEST, problem(errors), null);
    }

    private static ProblemDetail problem(List<String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }
}
