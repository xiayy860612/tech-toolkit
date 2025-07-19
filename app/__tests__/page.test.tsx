import { render, screen } from "@testing-library/react";
import Home from "../page";

test("renders page component", () => {
  render(<Home />);
  const testComponent = screen.queryByTestId("test-id");
  expect(testComponent).toBeInTheDocument();
});
